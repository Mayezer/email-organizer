from django.db import migrations, models


IMPORT_FUNCTION = """
CREATE OR REPLACE FUNCTION record_email_import_revision() RETURNS trigger
LANGUAGE plpgsql SET lock_timeout = '5s' AS $$
DECLARE confirmed_revision bigint;
BEGIN
    UPDATE email_import_checkpoint SET revision = revision + 1 WHERE singleton
    RETURNING revision INTO confirmed_revision;
    UPDATE emails SET import_revision = confirmed_revision {update_assignment}
    WHERE gmail_id = NEW.gmail_id AND import_revision IS NULL;
    RETURN NULL;
END;
$$;
"""


class Migration(migrations.Migration):
    dependencies = [("emails", "0011_email_sync_history")]

    operations = [
        migrations.AddField(
            model_name="emails", name="update_revision",
            field=models.BigIntegerField(editable=False, null=True),
        ),
        migrations.RunSQL(
            sql=IMPORT_FUNCTION.format(update_assignment=", update_revision = confirmed_revision") + """
                CREATE FUNCTION record_email_update_revision() RETURNS trigger
                LANGUAGE plpgsql SET lock_timeout = '5s' AS $$
                DECLARE confirmed_revision bigint;
                BEGIN
                    -- The same commit-ordered counter serves imports and updates;
                    -- import_revision remains immutable for navigation snapshots.
                    UPDATE email_import_checkpoint SET revision = revision + 1 WHERE singleton
                    RETURNING revision INTO confirmed_revision;
                    UPDATE emails SET update_revision = confirmed_revision WHERE gmail_id = NEW.gmail_id;
                    RETURN NULL;
                END;
                $$;
                CREATE CONSTRAINT TRIGGER email_update_revision_on_commit
                AFTER UPDATE OF processing_status, category, manual_category ON emails
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
                WHEN (OLD.processing_status IS DISTINCT FROM NEW.processing_status
                   OR OLD.category IS DISTINCT FROM NEW.category
                   OR OLD.manual_category IS DISTINCT FROM NEW.manual_category)
                EXECUTE FUNCTION record_email_update_revision();
            """,
            reverse_sql="""
                DROP TRIGGER email_update_revision_on_commit ON emails;
                DROP FUNCTION record_email_update_revision();
            """ + IMPORT_FUNCTION.format(update_assignment=""),
        ),
    ]
