from django.db import migrations, models


TRIGGER = """
DROP TRIGGER email_update_revision_on_commit ON emails;
CREATE CONSTRAINT TRIGGER email_update_revision_on_commit
AFTER UPDATE OF processing_status, category, manual_category, board_column, has_attachments, has_links{column}
ON emails DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
WHEN (OLD.processing_status IS DISTINCT FROM NEW.processing_status
   OR OLD.category IS DISTINCT FROM NEW.category
   OR OLD.manual_category IS DISTINCT FROM NEW.manual_category
   OR OLD.board_column IS DISTINCT FROM NEW.board_column
   OR OLD.has_attachments IS DISTINCT FROM NEW.has_attachments
   OR OLD.has_links IS DISTINCT FROM NEW.has_links{condition})
EXECUTE FUNCTION record_email_update_revision();
"""


class Migration(migrations.Migration):
    dependencies = [("emails", "0019_email_account_indexes")]

    operations = [
        migrations.AddField(
            model_name="emails", name="trash_pending_at",
            field=models.DateTimeField(editable=False, null=True),
        ),
        migrations.AddField(
            model_name="emails", name="trashed_at",
            field=models.DateTimeField(editable=False, null=True),
        ),
        migrations.RunSQL(
            sql=TRIGGER.format(
                column=", trashed_at",
                condition="\n   OR OLD.trashed_at IS DISTINCT FROM NEW.trashed_at",
            ),
            reverse_sql=TRIGGER.format(column="", condition=""),
        ),
    ]
