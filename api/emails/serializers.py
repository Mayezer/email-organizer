from rest_framework import serializers

from emails.models import Emails, EmailSyncJob


MANUAL_CATEGORIES = (
    "professional opportunities and recruitment",
    "event invitations",
    "social media updates",
    "advertising",
    "other",
)
BOARD_COLUMNS = (*MANUAL_CATEGORIES, "potential fraud", "")
AUTOMATIC_COLUMN = "automatic"


class EmailSerializers(serializers.ModelSerializer):
    class Meta:
        model = Emails
        exclude = ("manual_category", "board_column", "import_revision", "update_revision", "sync_account",
                   "trash_pending_at", "trashed_at")


class EmailManualCategorySerializer(serializers.Serializer):
    category = serializers.ChoiceField(choices=MANUAL_CATEGORIES)


class EmailBoardColumnSerializer(serializers.Serializer):
    column = serializers.ChoiceField(choices=(*BOARD_COLUMNS, AUTOMATIC_COLUMN))


class EmailTrashSerializer(serializers.Serializer):
    stage = serializers.ChoiceField(choices=("prepare", "confirm"))


class EmailMetadataSerializer(serializers.ModelSerializer):
    effective_category = serializers.SerializerMethodField()
    update_revision = serializers.CharField(read_only=True)
    model_fields = ("gmail_id", "thread_id", "sender", "subject", "received_at", "imported_at",
                    "processing_status", "category", "manual_category", "board_column", "update_revision",
                    "has_attachments", "has_links")

    def get_effective_category(self, email):
        return email.manual_category if email.manual_category is not None else email.category or ""

    class Meta:
        model = Emails
        fields = ("gmail_id", "thread_id", "sender", "subject", "received_at", "imported_at",
                  "processing_status", "effective_category", "board_column", "update_revision",
                  "has_attachments", "has_links")


class EmailChangeMetadataSerializer(EmailMetadataSerializer):
    model_fields = (*EmailMetadataSerializer.model_fields, "trashed_at")

    def to_representation(self, instance):
        data = super().to_representation(instance)
        if data["trashed_at"] is None:
            del data["trashed_at"]
        return data

    class Meta(EmailMetadataSerializer.Meta):
        fields = (*EmailMetadataSerializer.Meta.fields, "trashed_at")


class EmailSyncJobSerializer(serializers.ModelSerializer):
    class Meta:
        model = EmailSyncJob
        fields = ("id", "status", "imported_count", "analyzed_count", "analysis_failed_count", "created_at", "updated_at")
