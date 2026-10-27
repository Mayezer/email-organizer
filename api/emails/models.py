from django.db import models
from django.utils import timezone


class Emails(models.Model):
    gmail_id = models.TextField(primary_key=True)
    sender = models.TextField(blank=True, null=True)
    subject = models.TextField(blank=True, null=True)
    processing_status = models.CharField(
        max_length=20,
        default='PENDING',
    )
    imported_at = models.DateTimeField(default=timezone.now)
    processed_at = models.DateTimeField(blank=True, null=True)
    category = models.TextField(blank=True, null=True)
    summary = models.TextField(blank=True, null=True)
    requires_action = models.BooleanField(blank=True, null=True)

    class Meta:
        managed = False
        db_table = 'emails'

    def __str__(self):
        return self.sender or self.gmail_id

