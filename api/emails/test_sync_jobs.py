from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

from django.contrib.auth import get_user_model
from django.contrib.auth.models import Permission
from django.db import connections
from django.test import override_settings
from django.urls import reverse
from rest_framework.test import APIClient, APITransactionTestCase

from emails.models import EmailSyncJob


@override_settings(EMAIL_SYNC_ACCOUNT="sync@example.test")
class EmailSyncJobTest(APITransactionTestCase):
    def setUp(self):
        self.user = get_user_model().objects.create_user(username="sync-service")
        self.url = reverse("email-sync-jobs")

    def grant(self, *permissions):
        self.user.user_permissions.add(*Permission.objects.filter(
            content_type__app_label="emails", codename__in=permissions,
        ))

    def test_two_concurrent_requests_get_the_same_active_job(self):
        self.grant("add_emailsyncjob", "view_emailsyncjob")
        barrier = Barrier(2, timeout=5)

        def enqueue():
            client = APIClient()
            client.force_authenticate(user=get_user_model().objects.get(pk=self.user.pk))
            try:
                barrier.wait()
                response = client.post(self.url)
                return response.status_code, response.data
            finally:
                connections.close_all()

        with ThreadPoolExecutor(max_workers=2) as executor:
            first, second = executor.map(lambda _: enqueue(), range(2))
        self.assertEqual(first[0], 202)
        self.assertEqual(first, second)
        self.assertEqual(EmailSyncJob.objects.count(), 1)
        self.assertEqual(set(first[1]), {
            "id", "status", "imported_count", "analyzed_count", "analysis_failed_count", "created_at", "updated_at",
        })

        self.client.force_authenticate(user=self.user)
        job = EmailSyncJob.objects.get()
        self.assertEqual(job.account, "sync@example.test")
        EmailSyncJob.objects.filter(pk=job.pk).update(status="COMPLETED", imported_count=5)
        detail = self.client.get(reverse("email-sync-job", args=[job.pk]))
        self.assertEqual(detail.data["imported_count"], 5)
        self.assertNotEqual(self.client.post(self.url).data["id"], first[1]["id"])

    def test_permissions_and_account_binding(self):
        self.assertEqual(self.client.post(self.url).status_code, 401)
        self.grant("view_emailsyncjob")
        self.client.force_authenticate(user=self.user)
        self.assertEqual(self.client.post(self.url).status_code, 403)

        other = EmailSyncJob.objects.create(account="other@example.test")
        self.assertEqual(self.client.get(reverse("email-sync-job", args=[other.pk])).status_code, 404)

    @override_settings(EMAIL_SYNC_ACCOUNT="")
    def test_cannot_start_when_sync_is_disabled(self):
        self.grant("add_emailsyncjob", "view_emailsyncjob")
        self.client.force_authenticate(user=self.user)
        self.assertEqual(self.client.post(self.url).status_code, 503)
        self.assertFalse(EmailSyncJob.objects.exists())
