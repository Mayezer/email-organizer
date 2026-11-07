from django.contrib.postgres.operations import AddIndexConcurrently
from django.db import migrations, models
from django.db.models.functions import Coalesce


class Migration(migrations.Migration):
    atomic = False
    dependencies = [("emails", "0015_email_board_position")]

    operations = [
        AddIndexConcurrently(
            model_name="emails",
            index=models.Index(
                Coalesce("board_column", "manual_category", "category", models.Value(""), output_field=models.TextField()),
                models.F("processing_status"), models.F("imported_at").desc(), models.F("gmail_id").desc(),
                name="emails_board_position_idx",
            ),
        ),
    ]
