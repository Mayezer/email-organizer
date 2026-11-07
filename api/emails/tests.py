from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

from django.contrib.auth import get_user_model
from django.contrib.auth.models import Permission
from django.db import connection, connections, transaction
from django.urls import reverse
from rest_framework.test import APITestCase, APITransactionTestCase

from emails.models import Emails


class EmailListTest(APITestCase):
    def setUp(self):
        self.user = get_user_model().objects.create_user(username="reader")
        self.client.force_authenticate(user=self.user)

    def test_lists_every_status_without_category_filter_and_preserves_optional_filter(self):
        self.user.user_permissions.add(
            Permission.objects.get(content_type__app_label="emails", codename="view_emails")
        )
        Emails.objects.create(
            gmail_id="recruitment", thread_id="thread",
            processing_status="COMPLETED",
            category="professional opportunities and recruitment",
            security_status=Emails.SecurityStatus.SUSPICIOUS,
            security_reason="Solicitação de senha.",
        )
        Emails.objects.create(gmail_id="pending")
        Emails.objects.create(gmail_id="failed", processing_status="FAILED")

        result = self.client.get(reverse("emails"))
        self.assertEqual(result.status_code, 200)
        rows = {row["gmail_id"]: row for row in result.data}
        self.assertEqual(set(rows), {"recruitment", "pending", "failed"})
        self.assertEqual(rows["recruitment"]["thread_id"], "thread")
        self.assertEqual(rows["recruitment"]["security_status"], "SUSPICIOUS")
        self.assertEqual(rows["recruitment"]["security_reason"], "Solicitação de senha.")
        self.assertIsNone(rows["pending"]["security_status"])
        self.assertEqual(rows["failed"]["processing_status"], "FAILED")
        self.assertNotIn("import_revision", rows["pending"])

        result = self.client.get(reverse("emails"), {"category": "professional opportunities and recruitment"})
        self.assertEqual(result.status_code, 200)
        self.assertEqual([row["gmail_id"] for row in result.data], ["recruitment"])

    def test_denies_read_to_authenticated_user_without_email_permission(self):
        result = self.client.get(reverse("emails"))
        self.assertEqual(result.status_code, 403)


class EmailPageTest(APITestCase):
    @classmethod
    def setUpTestData(cls):
        cls.reader = get_user_model().objects.create_user(username="page-reader")
        cls.reader.user_permissions.add(
            Permission.objects.get(content_type__app_label="emails", codename="view_emails")
        )
        cls.imported_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
        Emails.objects.bulk_create([
            Emails(
                gmail_id=f"synthetic-{number:02d}",
                imported_at=cls.imported_at + timedelta(minutes=number // 4),
                received_at=None if number == 7 else cls.imported_at,
                sender="remetente@example.test",
                subject=f"Mensagem {number}",
                category=(
                    "professional opportunities and recruitment" if number % 2 == 0
                    else "event invitations"
                ),
                summary="Resumo fora da projeção de metadados.",
            )
            for number in range(12)
        ])

    def setUp(self):
        self.url = reverse("email-pages")
        self.client.force_authenticate(user=self.reader)

    def test_paginates_five_five_two_with_ties_and_a_newer_insert(self):
        first = self.client.get(self.url)
        self.assertEqual(first.status_code, 200)
        self.assertEqual(len(first.data["items"]), 5)
        self.assertIsNone(first.data["items"][-1]["received_at"])
        self.assertEqual(set(first.data["items"][0]), {
            "gmail_id", "thread_id", "sender", "subject", "received_at", "imported_at",
            "processing_status", "effective_category", "board_column", "update_revision",
            "has_attachments", "has_links",
        })
        first_cursor = first.data["next_cursor"]
        self.assertTrue(first_cursor)

        Emails.objects.create(
            gmail_id="newer", imported_at=self.imported_at + timedelta(minutes=3),
        )

        second = self.client.get(self.url, {"cursor": first_cursor})
        self.assertEqual(second.status_code, 200)
        self.assertEqual(len(second.data["items"]), 5)
        self.assertTrue(second.data["next_cursor"])

        third = self.client.get(self.url, {"cursor": second.data["next_cursor"]})
        self.assertEqual(third.status_code, 200)
        self.assertEqual(len(third.data["items"]), 2)
        self.assertIsNone(third.data["next_cursor"])

        ids = [item["gmail_id"] for page in (first, second, third) for item in page.data["items"]]
        self.assertEqual(ids, [f"synthetic-{number:02d}" for number in reversed(range(12))])
        fresh = self.client.get(self.url)
        self.assertEqual(fresh.data["items"][0]["gmail_id"], "newer")

    def test_rejects_invalid_cursors(self):
        cursor = self.client.get(self.url).data["next_cursor"]
        for invalid_cursor in ("", "invalid", f"{cursor}x"):
            with self.subTest(cursor=invalid_cursor):
                response = self.client.get(self.url, {"cursor": invalid_cursor})
                self.assertEqual(response.status_code, 400)
                self.assertIn("cursor", response.data)

    def test_paginates_only_the_selected_category_and_scopes_the_cursor(self):
        category = "professional opportunities and recruitment"
        first = self.client.get(self.url, {"category": category})
        self.assertEqual(first.status_code, 200)
        self.assertEqual(
            [item["gmail_id"] for item in first.data["items"]],
            ["synthetic-10", "synthetic-08", "synthetic-06", "synthetic-04", "synthetic-02"],
        )
        cursor = first.data["next_cursor"]
        self.assertTrue(cursor)
        second = self.client.get(self.url, {"category": category, "cursor": cursor})
        self.assertEqual(second.status_code, 200)
        self.assertEqual([item["gmail_id"] for item in second.data["items"]], ["synthetic-00"])
        self.assertIsNone(second.data["next_cursor"])
        self.assertEqual(self.client.get(self.url, {"cursor": cursor}).status_code, 400)
        self.assertEqual(
            self.client.get(self.url, {"category": "event invitations", "cursor": cursor}).status_code,
            400,
        )

    def test_empty_category_filter_includes_null_and_blank_categories(self):
        Emails.objects.filter(gmail_id="synthetic-00").update(category=None)
        Emails.objects.filter(gmail_id="synthetic-01").update(category="")

        result = self.client.get(self.url, {"category": ""})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(
            [item["gmail_id"] for item in result.data["items"]],
            ["synthetic-01", "synthetic-00"],
        )
        self.assertIsNone(result.data["next_cursor"])

    def test_empty_page_has_no_next_cursor(self):
        Emails.objects.all().delete()
        response = self.client.get(self.url)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data["items"], [])
        self.assertIsNone(response.data["next_cursor"])
        self.assertTrue(response.data["change_cursor"])

    def test_requires_authentication_and_email_view_permission(self):
        self.client.force_authenticate(user=None)
        self.assertEqual(self.client.get(self.url).status_code, 401)

        self.client.force_authenticate(user=get_user_model().objects.create_user(username="denied"))
        self.assertEqual(self.client.get(self.url).status_code, 403)


class EmailChangesTest(APITransactionTestCase):
    def setUp(self):
        self.url = reverse("email-changes")
        self.pages = reverse("email-pages")
        self.reader = get_user_model().objects.create_user(username="changes-reader")
        self.reader.user_permissions.add(
            Permission.objects.get(content_type__app_label="emails", codename="view_emails"),
        )
        self.client.force_authenticate(user=self.reader)

    def checkpoint(self):
        return self.client.get(self.pages).data["change_cursor"]

    def batch(self, cursor, **parameters):
        response = self.client.get(self.url, {"cursor": cursor, **parameters})
        self.assertEqual(response.status_code, 200)
        return response.data

    def ids(self, payload):
        return [item["gmail_id"] for item in payload["items"]]

    def test_drains_five_then_two_and_pins_each_read_to_its_checkpoint(self):
        Emails.objects.create(gmail_id="synthetic-existing")
        initial = self.checkpoint()
        expected = [f"synthetic-new-{number}" for number in range(7)]
        Emails.objects.bulk_create([Emails(gmail_id=gmail_id) for gmail_id in expected])
        first = self.batch(initial)
        self.assertEqual(self.ids(first), expected[:5])
        self.assertIsNotNone(first["until_cursor"])

        Emails.objects.create(gmail_id="synthetic-after-window")
        second = self.batch(first["change_cursor"], until=first["until_cursor"])
        self.assertEqual(self.ids(second), expected[5:])
        self.assertIsNone(second["until_cursor"])

        snapshot = self.client.get(self.pages, {"at": second["change_cursor"]}).data
        older = self.client.get(self.pages, {"cursor": snapshot["next_cursor"]}).data
        ids = self.ids(snapshot) + self.ids(older)
        self.assertCountEqual(ids, [*expected, "synthetic-existing"])

        third = self.batch(second["change_cursor"])
        self.assertEqual(self.ids(third), ["synthetic-after-window"])
        self.assertEqual(self.ids(self.batch(third["change_cursor"])), [])

    def test_later_commit_with_older_import_date_is_not_lost_or_inserted_into_old_pages(self):
        original = datetime(2026, 1, 2, tzinfo=timezone.utc)
        Emails.objects.bulk_create([
            Emails(gmail_id=f"synthetic-existing-{index}", imported_at=original)
            for index in range(6)
        ])
        page = self.client.get(self.pages).data

        def commit_independently():
            try:
                with connection.cursor() as cursor:
                    # The JDBC importer also inserts without supplying a revision.
                    cursor.execute("INSERT INTO emails (gmail_id) VALUES (%s)", ["synthetic-first-commit"])
            finally:
                connections.close_all()

        with ThreadPoolExecutor(max_workers=1) as pool:
            with transaction.atomic():
                Emails.objects.create(
                    gmail_id="synthetic-second-commit", imported_at=original - timedelta(days=1),
                )
                pool.submit(commit_independently).result(timeout=8)
                first = self.batch(page["change_cursor"])
                self.assertEqual(self.ids(first), ["synthetic-first-commit"])

        old_tail = self.client.get(self.pages, {"cursor": page["next_cursor"]}).data
        self.assertEqual(self.ids(old_tail), ["synthetic-existing-0"])
        second = self.batch(first["change_cursor"])
        self.assertEqual(self.ids(second), ["synthetic-second-commit"])
        self.assertEqual(self.ids(self.batch(second["change_cursor"])), [])

    def test_filtered_empty_slice_still_advances_and_rollback_publishes_nothing(self):
        initial = self.checkpoint()
        with transaction.atomic():
            Emails.objects.create(gmail_id="synthetic-rollback", category="event invitations")
            transaction.set_rollback(True)
        Emails.objects.bulk_create([Emails(gmail_id=f"synthetic-other-{index}") for index in range(5)])
        Emails.objects.create(gmail_id="synthetic-event", manual_category="event invitations")

        first = self.batch(initial, category="event invitations")
        self.assertEqual(self.ids(first), [])
        self.assertIsNotNone(first["until_cursor"])
        second = self.batch(
            first["change_cursor"], until=first["until_cursor"], category="event invitations",
        )
        self.assertEqual(self.ids(second), ["synthetic-event"])

    def test_rejects_invalid_or_navigation_cursors_and_requires_permission(self):
        Emails.objects.bulk_create([Emails(gmail_id=f"synthetic-{index}") for index in range(6)])
        page = self.client.get(self.pages).data
        for query in ({}, {"cursor": page["next_cursor"]}, {"cursor": page["change_cursor"], "until": "invalid"}):
            self.assertEqual(self.client.get(self.url, query).status_code, 400)

        self.client.force_authenticate(user=get_user_model().objects.create_user(username="no-changes"))
        self.assertEqual(self.client.get(self.url, {"cursor": page["change_cursor"]}).status_code, 403)


class EmailManualCategoryTest(APITestCase):
    def setUp(self):
        self.reader = get_user_model().objects.create_user(username="manual-category-reader")
        self.reader.user_permissions.add(
            Permission.objects.get(content_type__app_label="emails", codename="view_emails"),
        )
        self.client.force_authenticate(user=self.reader)
        self.email = Emails.objects.create(gmail_id="synthetic-manual")
        self.url = reverse("email-manual-category", args=[self.email.pk])

    def test_requires_change_permission_and_only_accepts_known_categories(self):
        payload = {"category": "event invitations"}
        self.assertEqual(self.client.patch(self.url, payload, format="json").status_code, 403)
        self.grant_change_permission()
        self.assertEqual(
            self.client.patch(self.url, {"category": "unknown"}, format="json").status_code,
            400,
        )
        self.email.refresh_from_db()
        self.assertIsNone(self.email.manual_category)

    def test_assignment_is_idempotent_and_wins_in_the_inbox_after_later_analysis(self):
        self.grant_change_permission()
        payload = {"category": "event invitations"}
        for _ in range(2):
            self.assertEqual(self.client.patch(self.url, payload, format="json").status_code, 204)
        Emails.objects.filter(pk=self.email.pk).update(category="other")
        self.email.refresh_from_db()
        self.assertEqual(self.email.manual_category, "event invitations")
        self.assertEqual(self.email.category, "other")
        self.assertEqual(
            self.client.patch(self.url, {"category": "advertising"}, format="json").status_code,
            409,
        )
        self.assertEqual(self.client.get(reverse("email-pages"), {"category": ""}).data["items"], [])
        result = self.client.get(reverse("email-pages"), payload)
        self.assertEqual([item["gmail_id"] for item in result.data["items"]], [self.email.pk])
        self.assertEqual(self.client.get(reverse("email-pages"), {"category": "other"}).data["items"], [])

    def test_preserves_category_when_analysis_finished_before_manual_assignment(self):
        self.grant_change_permission()
        Emails.objects.filter(pk=self.email.pk).update(category="other")
        response = self.client.patch(self.url, {"category": "event invitations"}, format="json")
        self.assertEqual(response.status_code, 409)
        self.email.refresh_from_db()
        self.assertIsNone(self.email.manual_category)

    def grant_change_permission(self):
        self.reader.user_permissions.add(
            Permission.objects.get(content_type__app_label="emails", codename="change_emails"),
        )
        self.reader.refresh_from_db()
        self.client.force_authenticate(user=get_user_model().objects.get(pk=self.reader.pk))
