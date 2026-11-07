from datetime import timedelta

from django.contrib.auth import get_user_model
from django.contrib.auth.models import Permission
from django.test import TransactionTestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from emails.import_cursor import encode_revision
from emails.models import Emails, EmailSyncJob
from emails.serializers import MANUAL_CATEGORIES


ACCOUNT = "linked@example.test"
OTHER = "other@example.test"
SERVICE = "app-test-service"
EMAILS = "/api/v1/emails/"
PAGES = "/api/v1/email-pages/"
CHANGES = "/api/v1/email-changes/"
SYNC = "/api/v1/email-sync-jobs/"


@override_settings(EMAIL_SYNC_ACCOUNT=ACCOUNT, EMAIL_APP_SERVICE_USERNAME=SERVICE)
class AccountAccessTests(TransactionTestCase):
    def setUp(self):
        for username in (SERVICE, "unlinked-user"):
            user = get_user_model().objects.create_user(username)
            user.user_permissions.set(Permission.objects.filter(content_type__app_label="emails"))
        for gmail_id, account in (("owned", ACCOUNT), ("foreign", OTHER), ("legacy", None)):
            Emails.objects.create(
                gmail_id=gmail_id, sync_account=account, processing_status="COMPLETED",
                category=MANUAL_CATEGORIES[0],
            )
        self.foreign_job = EmailSyncJob.objects.create(account=OTHER)

    def client_for(self, username, account=ACCOUNT):
        client = APIClient()
        if username is not None:
            client.force_authenticate(get_user_model().objects.get(username=username))
        if account is not None:
            client.credentials(HTTP_X_EMAIL_ACCOUNT=account)
        return client

    def test_rejects_anonymous_unlinked_and_mismatched_service_requests(self):
        self.assertEqual(self.client_for(None).get(EMAILS + "owned/").status_code, 401)
        for username, account in (("unlinked-user", ACCOUNT), (SERVICE, OTHER), (SERVICE, None)):
            with self.subTest(username=username, account=account):
                client = self.client_for(username, account)
                self.assertEqual(client.get(EMAILS + "owned/").status_code, 403)
                self.assertEqual(client.post(SYNC).status_code, 403)
        with self.settings(EMAIL_APP_SERVICE_USERNAME=""):
            self.assertEqual(self.client_for(SERVICE).get(PAGES).status_code, 403)

    def test_scopes_legacy_list_board_and_changes_to_linked_account(self):
        client = self.client_for(SERVICE)
        response = client.get(EMAILS, {"category": MANUAL_CATEGORIES[0], "account": OTHER})
        self.assertEqual(response.status_code, 200)
        self.assertEqual([row["gmail_id"] for row in response.json()], ["owned"])
        board = client.get(PAGES, {"board_column": MANUAL_CATEGORIES[0], "processing_status": "COMPLETED"})
        self.assertEqual(board.status_code, 200)
        self.assertEqual([row["gmail_id"] for row in board.json()["items"]], ["owned"])
        for include_updates in ("true", "false"):
            response = client.get(CHANGES, {"cursor": encode_revision(0, ACCOUNT), "include_updates": include_updates})
            self.assertEqual(response.status_code, 200)
            self.assertEqual([row["gmail_id"] for row in response.json()["items"]], ["owned"])
        for cursor in (encode_revision(0), encode_revision(0, OTHER)):
            self.assertEqual(client.get(CHANGES, {"cursor": cursor}).status_code, 400)

    def test_denies_access_and_mutations_for_a_known_foreign_or_legacy_id(self):
        client = self.client_for(SERVICE)
        self.assertEqual(client.get(EMAILS + "owned/").status_code, 200)
        for gmail_id in ("foreign", "legacy"):
            path = EMAILS + gmail_id + "/"
            self.assertEqual(client.get(path).status_code, 404)
            self.assertEqual(client.delete(path).status_code, 404)
            self.assertEqual(client.patch(path + "manual-category/", {"category": "other"}).status_code, 404)
            self.assertEqual(client.patch(path + "board-column/", {"column": "other"}).status_code, 404)
            email = Emails.objects.get(pk=gmail_id)
            self.assertIsNone(email.board_column)
            self.assertIsNone(email.manual_category)
        self.assertEqual(client.patch(EMAILS + "owned/board-column/", {"column": "other"}).status_code, 200)

    def test_trash_intent_stays_visible_until_confirmation_and_publishes_removal(self):
        client = self.client_for(SERVICE)
        path = EMAILS + "owned/trash/"
        snapshot = client.get(PAGES).json()["change_cursor"]
        self.assertEqual(client.patch(path, {"stage": "confirm"}).status_code, 409)
        self.assertEqual(client.patch(EMAILS + "foreign/trash/", {"stage": "prepare"}).status_code, 404)
        self.assertEqual(client.patch(path, {"stage": "prepare"}).json(), {"trashed": False})
        pending = Emails.objects.get(pk="owned").trash_pending_at
        self.assertIsNotNone(pending)
        self.assertEqual(client.patch(path, {"stage": "prepare"}).json(), {"trashed": False})
        self.assertEqual(Emails.objects.get(pk="owned").trash_pending_at, pending)
        self.assertEqual(client.get(PAGES).json()["items"][0]["gmail_id"], "owned")

        self.assertEqual(client.patch(path, {"stage": "confirm"}).json(), {"trashed": True})
        email = Emails.objects.get(pk="owned")
        self.assertIsNone(email.trash_pending_at)
        self.assertIsNotNone(email.trashed_at)
        self.assertEqual(client.patch(path, {"stage": "prepare"}).json(), {"trashed": True})
        self.assertEqual(client.patch(path, {"stage": "confirm"}).json(), {"trashed": True})
        self.assertEqual(Emails.objects.get(pk="owned").update_revision, email.update_revision)
        self.assertEqual(client.get(PAGES).json()["items"], [])
        self.assertEqual(client.get(PAGES, {"processing_status": "COMPLETED",
                                          "board_column": MANUAL_CATEGORIES[0]}).json()["items"], [])
        self.assertEqual(client.get(EMAILS, {"category": MANUAL_CATEGORIES[0]}).json(), [])
        self.assertEqual(client.get(EMAILS + "owned/conversation/").status_code, 404)
        self.assertEqual(client.get(CHANGES, {"cursor": encode_revision(0, ACCOUNT)}).json()["items"], [])
        changes = client.get(CHANGES, {"cursor": snapshot, "include_updates": "true"}).json()["items"]
        self.assertEqual([(item["gmail_id"], item["trashed_at"] is not None) for item in changes],
                         [("owned", True)])

    def test_trash_requires_session_binding_and_change_permission(self):
        path = EMAILS + "owned/trash/"
        self.assertEqual(self.client_for(None).patch(path, {"stage": "prepare"}).status_code, 401)
        self.assertEqual(self.client_for("unlinked-user").patch(path, {"stage": "prepare"}).status_code, 403)
        user = get_user_model().objects.get(username=SERVICE)
        user.user_permissions.remove(Permission.objects.get(content_type__app_label="emails",
                                                            codename="change_emails"))
        self.assertEqual(self.client_for(SERVICE).patch(path, {"stage": "prepare"}).status_code, 403)
        self.assertIsNone(Emails.objects.get(pk="owned").trash_pending_at)

    def test_sync_uses_linked_account_and_hides_foreign_job(self):
        client = self.client_for(SERVICE)
        self.assertEqual(client.get(f"{SYNC}{self.foreign_job.pk}/").status_code, 404)
        response = client.post(SYNC, {"account": OTHER})
        self.assertEqual(response.status_code, 202)
        job = EmailSyncJob.objects.get(pk=response.json()["id"])
        self.assertEqual(job.account, ACCOUNT)
        self.assertEqual(client.get(f"{SYNC}{job.pk}/").status_code, 200)
        self.assertNotIn("account", response.json())

    def test_conversation_returns_ordered_ids_from_only_the_linked_account(self):
        client = self.client_for(SERVICE)
        start = timezone.now()
        Emails.objects.filter(pk__in=("owned", "foreign", "legacy")).update(
            thread_id="shared", received_at=start,
        )
        for index in range(1, 7):
            Emails.objects.create(
                gmail_id=f"reply-{index}", thread_id="shared", sync_account=ACCOUNT,
                received_at=start + timedelta(minutes=index),
            )
        self.assertEqual(client.get(EMAILS + "owned/conversation/").json(), {
            "gmail_ids": ["owned", "reply-3", "reply-4", "reply-5", "reply-6"],
            "partial": True,
        })
        self.assertEqual(client.get(EMAILS + "foreign/conversation/").status_code, 404)
        self.assertEqual(client.get(EMAILS + "legacy/conversation/").status_code, 404)
        self.assertEqual(self.client_for(None).get(EMAILS + "owned/conversation/").status_code, 401)

    def test_conversation_without_thread_contains_only_the_requested_message(self):
        client = self.client_for(SERVICE)
        self.assertEqual(client.get(EMAILS + "owned/conversation/").json(), {
            "gmail_ids": ["owned"], "partial": False,
        })
        with self.settings(EMAIL_APP_SERVICE_USERNAME=""):
            client = self.client_for(SERVICE, None)
            self.assertEqual(client.get(EMAILS + "owned/conversation/").status_code, 403)
