from django.contrib.auth import get_user_model
from django.contrib.auth.models import Permission
from django.urls import reverse
from rest_framework.test import APITransactionTestCase

from emails.models import Emails


RECRUITMENT = "professional opportunities and recruitment"
EVENTS = "event invitations"


class BoardMoveTest(APITransactionTestCase):
    def setUp(self):
        self.email = Emails.objects.create(
            gmail_id="synthetic-vacancy", processing_status="COMPLETED",
            category=RECRUITMENT, subject="Vaga sintética",
        )
        self.editor = get_user_model().objects.create_user(username="board-editor")
        self.editor.user_permissions.add(*Permission.objects.filter(
            content_type__app_label="emails", codename__in=["view_emails", "change_emails"],
        ))
        self.client.force_authenticate(user=self.editor)
        self.url = reverse("email-board-column", args=[self.email.pk])

    def move(self, column):
        return self.client.patch(self.url, {"column": column}, format="json")

    def column(self, value, **params):
        return self.client.get(reverse("email-pages"), {
            "processing_status": "COMPLETED", "board_column": value, **params,
        })

    def test_move_persists_and_keeps_the_opportunity_category(self):
        initial = self.column(RECRUITMENT).data
        moved = self.move(EVENTS)
        self.assertEqual(moved.status_code, 200)
        self.assertEqual(moved.data["effective_category"], RECRUITMENT)
        self.assertEqual(moved.data["board_column"], EVENTS)
        self.assertEqual(self.column(RECRUITMENT).data["items"], [])
        self.assertEqual(self.column(EVENTS).data["items"], [moved.data])

        self.email.refresh_from_db()
        self.assertEqual(self.email.category, RECRUITMENT)
        self.assertIsNone(self.email.manual_category)
        self.assertEqual(self.email.board_column, EVENTS)
        original = self.client.get(reverse("emails"), {"category": RECRUITMENT})
        self.assertEqual([item["gmail_id"] for item in original.data], [self.email.pk])
        changes = self.client.get(reverse("email-changes"), {
            "cursor": initial["change_cursor"], "include_updates": "true",
        })
        self.assertEqual(changes.data["items"], [moved.data])
        self.assertEqual(self.move(EVENTS).data["update_revision"], moved.data["update_revision"])

    def test_uncategorized_destination_and_reset_to_manual_category_are_distinct(self):
        Emails.objects.filter(pk=self.email.pk).update(manual_category=EVENTS)
        self.assertEqual(self.move("").data["board_column"], "")
        self.assertEqual([item["gmail_id"] for item in self.column("").data["items"]], [self.email.pk])
        self.assertIsNone(self.move("automatic").data["board_column"])
        self.assertEqual([item["gmail_id"] for item in self.column(EVENTS).data["items"]], [self.email.pk])

    def test_invalid_destination_or_permission_failure_preserves_position(self):
        self.move(EVENTS)
        for value in ("PENDING", "unknown", None):
            self.assertEqual(self.move(value).status_code, 400)
        reader = get_user_model().objects.create_user(username="board-reader")
        reader.user_permissions.add(Permission.objects.get(codename="view_emails"))
        self.client.force_authenticate(user=reader)
        self.assertEqual(self.move(RECRUITMENT).status_code, 403)
        self.email.refresh_from_db()
        self.assertEqual(self.email.board_column, EVENTS)

    def test_only_completed_cards_can_move_and_missing_card_returns_not_found(self):
        for state in ("PENDING", "FAILED"):
            Emails.objects.filter(pk=self.email.pk).update(processing_status=state)
            self.assertEqual(self.move(EVENTS).status_code, 409)
        self.email.delete()
        self.assertEqual(self.move(EVENTS).status_code, 404)

    def test_board_cursor_is_bound_to_column_and_not_reusable_as_category_cursor(self):
        Emails.objects.bulk_create([
            Emails(gmail_id=f"synthetic-event-{i}", category=EVENTS, processing_status="COMPLETED")
            for i in range(6)
        ])
        cursor = self.column(EVENTS).data["next_cursor"]
        self.assertTrue(cursor)
        self.assertEqual(len(self.column(EVENTS, cursor=cursor).data["items"]), 1)
        self.assertEqual(self.column(RECRUITMENT, cursor=cursor).status_code, 400)
        self.assertEqual(self.column(EVENTS, category=EVENTS).status_code, 400)
        self.assertEqual(self.client.get(reverse("email-pages"), {
            "cursor": cursor, "processing_status": "COMPLETED", "category": EVENTS,
        }).status_code, 400)
