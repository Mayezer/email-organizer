from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0020_email_trash")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["sync_account", "trash_pending_at", "gmail_id"],
                condition=models.Q(trash_pending_at__isnull=False),
                name="emails_trash_pending_idx",
            ),
        ),
    ]
