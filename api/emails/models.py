from uuid import uuid4

from django.db import models
from django.db.models.functions import Coalesce, Now


class Emails(models.Model):
    class SecurityStatus(models.TextChoices):
        NO_ALERT = "NO_ALERT"
        SUSPICIOUS = "SUSPICIOUS"
        INCONCLUSIVE = "INCONCLUSIVE"

    gmail_id = models.TextField(primary_key=True)
    thread_id = models.TextField(blank=True, null=True)
    received_at = models.DateTimeField(blank=True, null=True)
    sender = models.TextField(blank=True, null=True)
    subject = models.TextField(blank=True, null=True)
    processing_status = models.CharField(max_length=20, db_default='PENDING',)
    imported_at = models.DateTimeField(db_default=Now())
    import_revision = models.BigIntegerField(editable=False, null=True)
    update_revision = models.BigIntegerField(editable=False, null=True)
    sync_account = models.TextField(editable=False, null=True)
    processed_at = models.DateTimeField(blank=True, null=True)
    category = models.TextField(blank=True, null=True)
    manual_category = models.TextField(blank=True, null=True)
    summary = models.TextField(blank=True, null=True)
    requires_action = models.BooleanField(blank=True, null=True)
    security_status = models.CharField(
        max_length=20, choices=SecurityStatus.choices, blank=True, null=True,
    )
    security_reason = models.TextField(blank=True, null=True)

    class Meta:
        managed = True
        db_table = 'emails'
        indexes = [
            models.Index(fields=["thread_id", "received_at"], name="emails_thread_received_idx"),
            models.Index(fields=["received_at"], name="emails_received_idx"),
            models.Index(
                fields=["sync_account", "received_at", "gmail_id"],
                condition=models.Q(processing_status="PENDING", sync_account__isnull=False),
                name="emails_sync_pending_idx",
            ),
            models.Index(fields=["-imported_at", "-gmail_id"], name="emails_imported_gmail_idx"),
            models.Index(
                fields=["import_revision"], condition=models.Q(import_revision__isnull=False),
                name="emails_import_revision_idx",
            ),
            models.Index(
                fields=["update_revision"], condition=models.Q(update_revision__isnull=False),
                name="emails_update_revision_idx",
            ),
            models.Index(
                Coalesce("category", models.Value(""), output_field=models.TextField()),
                models.F("imported_at").desc(),
                models.F("gmail_id").desc(),
                name="emails_category_page_idx",
            ),
            models.Index(
                Coalesce("manual_category", "category", models.Value(""), output_field=models.TextField()),
                models.F("imported_at").desc(),
                models.F("gmail_id").desc(),
                name="emails_effective_page_idx",
            ),
        ]

    def __str__(self):
        return self.sender or self.gmail_id


class EmailSyncJob(models.Model):
    class Status(models.TextChoices):
        IMPORTING = "IMPORTING"
        ANALYZING = "ANALYZING"
        COMPLETED = "COMPLETED"
        FAILED = "FAILED"

    id = models.UUIDField(primary_key=True, default=uuid4, editable=False)
    account = models.TextField()
    status = models.CharField(max_length=12, choices=Status.choices, db_default=Status.IMPORTING)
    page_token = models.TextField(null=True)
    full_history_id = models.TextField(null=True)
    history_offset = models.PositiveIntegerField(db_default=0)
    history_recovery_started = models.BooleanField(db_default=False)
    lease_token = models.UUIDField(null=True)
    lease_until = models.DateTimeField(null=True)
    available_at = models.DateTimeField(db_default=Now())
    attempts = models.PositiveSmallIntegerField(db_default=0)
    imported_count = models.PositiveBigIntegerField(db_default=0)
    analyzed_count = models.PositiveBigIntegerField(db_default=0)
    analysis_failed_count = models.PositiveBigIntegerField(db_default=0)
    created_at = models.DateTimeField(db_default=Now())
    updated_at = models.DateTimeField(db_default=Now())

    class Meta:
        db_table = "email_sync_jobs"
        constraints = [
            models.UniqueConstraint(
                fields=["account"], condition=models.Q(status__in=["IMPORTING", "ANALYZING"]),
                name="email_sync_one_active_account",
            ),
        ]


class EmailSyncCheckpoint(models.Model):
    account = models.TextField(primary_key=True)
    history_id = models.TextField()

    class Meta:
        db_table = "email_sync_checkpoints"
