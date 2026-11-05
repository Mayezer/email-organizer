from django.db import models
from django.utils import timezone
from django.db.models.functions import Now;


class Emails(models.Model):
    gmail_id = models.TextField(primary_key=True)
    sender = models.TextField(blank=True, null=True)
    subject = models.TextField(blank=True, null=True)
    processing_status = models.CharField(max_length=20, db_default='PENDING',)
    imported_at = models.DateTimeField(db_default=Now())
    processed_at = models.DateTimeField(blank=True, null=True)
    category = models.TextField(blank=True, null=True)
    summary = models.TextField(blank=True, null=True)
    requires_action = models.BooleanField(blank=True, null=True)

    class Meta:
        managed = True
        db_table = 'emails'

    def __str__(self):
        return self.sender or self.gmail_id
