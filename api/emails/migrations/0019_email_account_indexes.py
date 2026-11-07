from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0018_email_indicators_pending_index")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["sync_account", "-imported_at", "-gmail_id"],
                condition=models.Q(sync_account__isnull=False),
                name="emails_account_page_idx",
            ),
        ),
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["sync_account", "import_revision"],
                condition=models.Q(sync_account__isnull=False, import_revision__isnull=False),
                name="emails_account_import_idx",
            ),
        ),
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["sync_account", "update_revision"],
                condition=models.Q(sync_account__isnull=False, update_revision__isnull=False),
                name="emails_account_update_idx",
            ),
        ),
    ]
