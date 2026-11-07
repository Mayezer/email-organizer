from django.db import migrations, models


TRIGGER = """
CREATE CONSTRAINT TRIGGER email_update_revision_on_commit
AFTER UPDATE OF processing_status, category, manual_category {extra_columns} ON emails
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
WHEN (OLD.processing_status IS DISTINCT FROM NEW.processing_status
   OR OLD.category IS DISTINCT FROM NEW.category
   OR OLD.manual_category IS DISTINCT FROM NEW.manual_category {extra_condition})
EXECUTE FUNCTION record_email_update_revision();
"""
DROP_TRIGGER = "DROP TRIGGER email_update_revision_on_commit ON emails;"


class Migration(migrations.Migration):
    dependencies = [("emails", "0014_email_board_indexes")]

    operations = [
        migrations.AddField(
            model_name="emails",
            name="board_column",
            field=models.TextField(editable=False, null=True),
        ),
        migrations.RunSQL(
            sql=DROP_TRIGGER + TRIGGER.format(
                extra_columns=", board_column",
                extra_condition="OR OLD.board_column IS DISTINCT FROM NEW.board_column",
            ),
            reverse_sql=DROP_TRIGGER + TRIGGER.format(extra_columns="", extra_condition=""),
        ),
    ]
