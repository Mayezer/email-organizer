from concurrent.futures import ThreadPoolExecutor

from django.contrib.auth import get_user_model
from django.contrib.auth.models import Permission
from django.db import connections, transaction
from django.urls import reverse
from rest_framework.test import APITransactionTestCase

from emails.models import Emails


class ProcessingUpdatesTest(APITransactionTestCase):
    def setUp(self):
        reader = get_user_model().objects.create_user(username="updates-reader")
        reader.user_permissions.add(Permission.objects.get(codename="view_emails"))
        self.client.force_authenticate(user=reader)

    def checkpoint(self):
        return self.client.get(reverse("email-pages")).data["change_cursor"]

    def updates(self, after, **params):
        response = self.client.get(reverse("email-changes"), {
            "cursor": after, "include_updates": "true", **params,
        })
        self.assertEqual(response.status_code, 200)
        return response.data

    def test_processing_failure_and_manual_category_emit_confirmed_updates(self):
        email = Emails.objects.create(gmail_id="synthetic-state")
        after = self.checkpoint()
        original_import_revision = Emails.objects.get(pk=email.pk).import_revision
        Emails.objects.filter(pk=email.pk).update(processing_status="COMPLETED", category="other")
        result = self.updates(after)
        self.assertEqual(result["items"][0]["processing_status"], "COMPLETED")
        self.assertEqual(result["items"][0]["effective_category"], "other")
        Emails.objects.filter(pk=email.pk).update(manual_category="event invitations")
        manual = self.updates(result["change_cursor"])
        self.assertEqual(manual["items"][0]["effective_category"], "event invitations")
        Emails.objects.filter(pk=email.pk).update(processing_status="FAILED")
        failure = self.updates(manual["change_cursor"])
        self.assertEqual(failure["items"][0]["processing_status"], "FAILED")
        self.assertEqual(self.updates(failure["change_cursor"])["items"], [])
        self.assertEqual(Emails.objects.get(pk=email.pk).import_revision, original_import_revision)
        # The old endpoint mode still returns imports only.
        old = self.client.get(reverse("email-changes"), {"cursor": after})
        self.assertEqual(old.data["items"], [])

    def test_backfilled_indicators_publish_an_update_without_changing_classification(self):
        email = Emails.objects.create(
            gmail_id="synthetic-legacy", processing_status="COMPLETED", category="other",
        )
        before = self.client.get(reverse("email-pages")).data
        self.assertIsNone(before["items"][0]["has_attachments"])
        self.assertIsNone(before["items"][0]["has_links"])
        Emails.objects.filter(pk=email.pk).update(has_attachments=False, has_links=True)

        updated = self.updates(before["change_cursor"])
        card = updated["items"][0]
        self.assertEqual(card["gmail_id"], email.pk)
        self.assertEqual(card["effective_category"], "other")
        self.assertEqual(card["processing_status"], "COMPLETED")
        self.assertIs(card["has_attachments"], False)
        self.assertIs(card["has_links"], True)
        self.assertEqual(self.updates(updated["change_cursor"])["items"], [])

    def test_updates_are_paged_five_five_two_and_do_not_publish_rolled_back_changes(self):
        Emails.objects.bulk_create([Emails(gmail_id=f"synthetic-{i}") for i in range(12)])
        after = self.checkpoint()
        with transaction.atomic():
            Emails.objects.filter(pk="synthetic-0").update(processing_status="FAILED")
            transaction.set_rollback(True)
        self.assertEqual(self.updates(after)["items"], [])
        Emails.objects.update(processing_status="COMPLETED", category="other")
        first = self.updates(after)
        second = self.updates(first["change_cursor"], until=first["until_cursor"])
        third = self.updates(second["change_cursor"], until=second["until_cursor"])
        self.assertEqual([len(batch["items"]) for batch in (first, second, third)], [5, 5, 2])
        self.assertEqual(len({row["gmail_id"] for batch in (first, second, third) for row in batch["items"]}), 12)

    def test_a_later_commit_is_seen_after_an_earlier_independent_update(self):
        Emails.objects.bulk_create([Emails(gmail_id=f"synthetic-{i}") for i in range(2)])
        after = self.checkpoint()

        def independent():
            try:
                Emails.objects.filter(pk="synthetic-1").update(processing_status="FAILED")
            finally:
                connections.close_all()

        with ThreadPoolExecutor(max_workers=1) as pool:
            with transaction.atomic():
                Emails.objects.filter(pk="synthetic-0").update(processing_status="COMPLETED")
                pool.submit(independent).result(timeout=8)
                first = self.updates(after)
                self.assertEqual([row["gmail_id"] for row in first["items"]], ["synthetic-1"])
        second = self.updates(first["change_cursor"])
        self.assertEqual([row["gmail_id"] for row in second["items"]], ["synthetic-0"])

    def test_an_update_moving_beyond_a_pinned_window_is_seen_in_the_next_cycle(self):
        Emails.objects.bulk_create([Emails(gmail_id=f"synthetic-{i}") for i in range(7)])
        after = self.checkpoint()
        Emails.objects.update(processing_status="COMPLETED", category="other")
        first = self.updates(after)
        first_ids = {row["gmail_id"] for row in first["items"]}
        moved = next(email.pk for email in Emails.objects.all() if email.pk not in first_ids)
        Emails.objects.filter(pk=moved).update(processing_status="FAILED")
        second = self.updates(first["change_cursor"], until=first["until_cursor"])
        final = self.updates(second["change_cursor"])
        self.assertIn(moved, [row["gmail_id"] for row in final["items"]])
