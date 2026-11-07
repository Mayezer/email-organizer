from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0009_email_sync_jobs")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["sync_account", "received_at", "gmail_id"],
                condition=models.Q(processing_status="PENDING", sync_account__isnull=False),
                name="emails_sync_pending_idx",
            ),
        ),
    ]
