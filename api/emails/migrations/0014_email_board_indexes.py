from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models
from django.db.models.functions import Coalesce


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0013_email_update_revision_index")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                fields=["processing_status", "-imported_at", "-gmail_id"], name="emails_status_page_idx",
            ),
        ),
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                Coalesce("manual_category", "category", models.Value(""), output_field=models.TextField()),
                models.F("processing_status"), models.F("imported_at").desc(), models.F("gmail_id").desc(),
                name="emails_board_category_idx",
            ),
        ),
    ]
