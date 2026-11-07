from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("emails", "0006_email_effective_category_index")]

    operations = [
        migrations.AddField(
            model_name="emails",
            name="import_revision",
            field=models.BigIntegerField(editable=False, null=True),
        ),
        migrations.RunSQL(
            sql="""
                CREATE TABLE email_import_checkpoint (
                    singleton boolean PRIMARY KEY CHECK (singleton),
                    revision bigint NOT NULL
                );
                INSERT INTO email_import_checkpoint VALUES (true, 0);

                CREATE FUNCTION record_email_import_revision() RETURNS trigger
                LANGUAGE plpgsql SET lock_timeout = '5s' AS $$
                DECLARE
                    confirmed_revision bigint;
                BEGIN
                    -- This deferred trigger runs during commit. The counter row is locked
                    -- until the transaction ends, so another writer cannot publish a later
                    -- revision while this one is still invisible to readers.
                    UPDATE email_import_checkpoint
                    SET revision = revision + 1
                    WHERE singleton
                    RETURNING revision INTO confirmed_revision;

                    UPDATE emails SET import_revision = confirmed_revision
                    WHERE gmail_id = NEW.gmail_id AND import_revision IS NULL;
                    RETURN NULL;
                END;
                $$;

                CREATE CONSTRAINT TRIGGER email_import_revision_on_commit
                AFTER INSERT ON emails DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW EXECUTE FUNCTION record_email_import_revision();
            """,
            reverse_sql="""
                DROP TRIGGER email_import_revision_on_commit ON emails;
                DROP FUNCTION record_email_import_revision();
                DROP TABLE email_import_checkpoint;
            """,
        ),
    ]
