from django.db import migrations, models


TRIGGER = """
DROP TRIGGER email_update_revision_on_commit ON emails;
CREATE CONSTRAINT TRIGGER email_update_revision_on_commit
AFTER UPDATE OF processing_status, category, manual_category, board_column{columns} ON emails
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
WHEN (OLD.processing_status IS DISTINCT FROM NEW.processing_status
   OR OLD.category IS DISTINCT FROM NEW.category
   OR OLD.manual_category IS DISTINCT FROM NEW.manual_category
   OR OLD.board_column IS DISTINCT FROM NEW.board_column{condition})
EXECUTE FUNCTION record_email_update_revision();
"""


class Migration(migrations.Migration):
    dependencies = [("emails", "0016_email_board_position_index")]

    operations = [
        migrations.AddField(
            model_name="emails", name="has_attachments",
            field=models.BooleanField(editable=False, null=True),
        ),
        migrations.AddField(
            model_name="emails", name="has_links",
            field=models.BooleanField(editable=False, null=True),
        ),
        migrations.RunSQL(
            sql=TRIGGER.format(
                columns=", has_attachments, has_links",
                condition="""
   OR OLD.has_attachments IS DISTINCT FROM NEW.has_attachments
   OR OLD.has_links IS DISTINCT FROM NEW.has_links""",
            ),
            reverse_sql=TRIGGER.format(columns="", condition=""),
        ),
    ]
