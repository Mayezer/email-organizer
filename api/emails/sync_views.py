from uuid import uuid4

from django.conf import settings
from django.db import OperationalError, connection, transaction
from rest_framework import status
from rest_framework.exceptions import APIException, NotFound
from rest_framework.permissions import BasePermission, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from emails.models import EmailSyncJob
from emails.permissions import EmailAccountPermission
from emails.serializers import EmailSyncJobSerializer


class SyncUnavailable(APIException):
    status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    default_detail = "Sincronização indisponível. Tente novamente mais tarde."


class EmailSyncPermission(BasePermission):
    def has_permission(self, request, view):
        if request.method == "POST":
            return request.user.has_perms(("emails.add_emailsyncjob", "emails.view_emailsyncjob"))
        return request.user.has_perm("emails.view_emailsyncjob")


class EmailSyncView(APIView):
    permission_classes = (IsAuthenticated, EmailAccountPermission, EmailSyncPermission)

    def post(self, request):
        account = request.email_account or settings.EMAIL_SYNC_ACCOUNT
        if not account:
            raise SyncUnavailable()

        # The partial unique index serializes concurrent requests for the same
        # account, including when a worker is about to finish the previous job.
        try:
            with transaction.atomic(), connection.cursor() as cursor:
                cursor.execute("SET LOCAL lock_timeout = '2s'")
                cursor.execute(
                    """
                    INSERT INTO email_sync_jobs (id, account) VALUES (%s, %s)
                    ON CONFLICT (account) WHERE status IN ('IMPORTING', 'ANALYZING')
                    DO UPDATE SET account = EXCLUDED.account
                    RETURNING id
                    """,
                    (uuid4(), account),
                )
                job = EmailSyncJob.objects.get(pk=cursor.fetchone()[0])
        except OperationalError as exc:
            raise SyncUnavailable() from exc
        return Response(EmailSyncJobSerializer(job).data, status=status.HTTP_202_ACCEPTED)


class EmailSyncDetailView(APIView):
    permission_classes = (IsAuthenticated, EmailAccountPermission, EmailSyncPermission)

    def get(self, request, pk):
        try:
            job = EmailSyncJob.objects.get(pk=pk, account=request.email_account or settings.EMAIL_SYNC_ACCOUNT)
        except EmailSyncJob.DoesNotExist as exc:
            raise NotFound() from exc
        return Response(EmailSyncJobSerializer(job).data)
