from datetime import datetime, timedelta, timezone

from django.contrib.auth import get_user_model
from django.contrib.auth.models import Permission
from django.urls import reverse
from rest_framework.test import APITestCase

from emails.models import Emails


class BoardPagesTest(APITestCase):
    @classmethod
    def setUpTestData(cls):
        cls.reader = get_user_model().objects.create_user(username="board-reader")
        cls.reader.user_permissions.add(Permission.objects.get(codename="view_emails"))
        start = datetime(2026, 11, 1, tzinfo=timezone.utc)
        Emails.objects.bulk_create([
            Emails(
                gmail_id=f"synthetic-{prefix}-{number:02d}", processing_status="COMPLETED",
                category=category, imported_at=start + timedelta(seconds=number),
            )
            for prefix, category in (("vaga", "professional opportunities and recruitment"),
                                     ("evento", "event invitations"))
            for number in range(12)
        ])
        Emails.objects.create(gmail_id="synthetic-pending", processing_status="PENDING", category="event invitations")
        Emails.objects.create(gmail_id="synthetic-failed", processing_status="FAILED", category="event invitations")
        Emails.objects.create(gmail_id="synthetic-uncategorized", processing_status="COMPLETED")
        Emails.objects.create(
            gmail_id="synthetic-manual", processing_status="COMPLETED",
            category="professional opportunities and recruitment", manual_category="other",
        )

    def setUp(self):
        self.client.force_authenticate(user=self.reader)
        self.url = reverse("email-pages")

    def test_categories_have_independent_five_five_two_pages(self):
        for category, prefix in (("professional opportunities and recruitment", "vaga"),
                                 ("event invitations", "evento")):
            with self.subTest(category=category):
                params = {"category": category, "processing_status": "COMPLETED"}
                first = self.client.get(self.url, params).data
                second = self.client.get(self.url, {**params, "cursor": first["next_cursor"]}).data
                third = self.client.get(self.url, {**params, "cursor": second["next_cursor"]}).data
                self.assertEqual([len(page["items"]) for page in (first, second, third)], [5, 5, 2])
                ids = [row["gmail_id"] for page in (first, second, third) for row in page["items"]]
                self.assertEqual(ids, [f"synthetic-{prefix}-{n:02d}" for n in reversed(range(12))])

    def test_pending_failed_uncategorized_and_manual_assignment_are_disjoint(self):
        queries = [
            ({"processing_status": "PENDING"}, "synthetic-pending"),
            ({"processing_status": "FAILED"}, "synthetic-failed"),
            ({"processing_status": "COMPLETED", "category": ""}, "synthetic-uncategorized"),
            ({"processing_status": "COMPLETED", "category": "other"}, "synthetic-manual"),
        ]
        for params, expected in queries:
            result = self.client.get(self.url, params)
            self.assertEqual(result.status_code, 200)
            self.assertEqual([row["gmail_id"] for row in result.data["items"]], [expected])

    def test_cursor_cannot_cross_status_or_category_and_invalid_status_is_rejected(self):
        params = {"category": "event invitations", "processing_status": "COMPLETED"}
        cursor = self.client.get(self.url, params).data["next_cursor"]
        for changed in ({**params, "processing_status": "PENDING"}, {"category": "event invitations"},
                        {**params, "category": "other"}):
            self.assertEqual(self.client.get(self.url, {**changed, "cursor": cursor}).status_code, 400)
        for invalid in ("", "unknown", "pending"):
            self.assertEqual(self.client.get(self.url, {"processing_status": invalid}).status_code, 400)
