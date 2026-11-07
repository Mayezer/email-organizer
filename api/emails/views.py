from datetime import datetime

from django.core import signing
from django.db import transaction
from django.db.models import BooleanField, F, Q, TextField, Value
from django.db.models.expressions import RawSQL
from django.db.models.functions import Coalesce, Now
from rest_framework import generics, status
from rest_framework.exceptions import NotFound, PermissionDenied, ValidationError
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response

from emails.import_cursor import decode_revision, encode_revision, latest_revision
from emails.models import Emails
from emails.permissions import EmailAccountPermission, EmailPermissionClass
from emails.serializers import (
    AUTOMATIC_COLUMN, BOARD_COLUMNS, EmailBoardColumnSerializer, EmailChangeMetadataSerializer,
    EmailManualCategorySerializer, EmailMetadataSerializer, EmailSerializers, EmailTrashSerializer,
)


class AccountEmailView:
    permission_classes = (IsAuthenticated, EmailAccountPermission, EmailPermissionClass)

    def get_queryset(self):
        return self.account_emails().filter(trashed_at__isnull=True)

    def account_emails(self):
        queryset = Emails.objects.all()
        account = self.request.email_account
        return queryset if account is None else queryset.filter(sync_account=account)


class EmailListCreateView(AccountEmailView, generics.ListCreateAPIView):

    serializer_class = EmailSerializers

    def get_queryset(self):
        queryset = super().get_queryset()
        category = self.request.query_params.get("category")
        if category != None:
            queryset = queryset.filter(category = category)
        return queryset

    def perform_create(self, serializer):
        serializer.save(sync_account=self.request.email_account)


class EmailPageView(AccountEmailView, generics.GenericAPIView):
    serializer_class = EmailMetadataSerializer
    page_size = 5
    cursor_salt = "emails.page.v2"

    def get(self, request):
        queryset = self.get_queryset().only(*self.serializer_class.model_fields).order_by(
            "-imported_at", "-gmail_id",
        )
        category = request.query_params.get("category")
        cursor_salt = self.cursor_salt
        account = request.email_account
        if account is not None:
            cursor_salt += f".account:{account}"
        processing_status = request.query_params.get("processing_status")
        if processing_status is not None:
            if processing_status not in ("PENDING", "COMPLETED", "FAILED"):
                raise ValidationError({"processing_status": "Estado de processamento inválido."})
            queryset = queryset.filter(processing_status=processing_status)
            cursor_salt += f".status:{processing_status}"
        if category is not None:
            queryset = queryset.alias(
                category_filter=Coalesce("manual_category", "category", Value(""), output_field=TextField()),
            ).filter(category_filter=category)
            cursor_salt += f".category:{category}"

        board_column = request.query_params.get("board_column")
        if board_column is not None:
            if category is not None or processing_status != "COMPLETED" or board_column not in BOARD_COLUMNS:
                raise ValidationError({"board_column": "Selecione uma coluna do quadro para mensagens concluídas."})
            queryset = queryset.alias(
                position=Coalesce("board_column", "manual_category", "category", Value(""), output_field=TextField()),
            ).filter(position=board_column)
            cursor_salt += f".board:{board_column}"

        cursor = request.query_params.get("cursor")
        at = request.query_params.get("at")
        if cursor is not None:
            if at is not None:
                raise ValidationError({"at": "Use este cursor apenas na primeira página."})
            try:
                revision, imported_at, gmail_id = signing.loads(cursor, salt=cursor_salt)
            except signing.BadSignature as exc:
                raise ValidationError({"cursor": "Cursor inválido."}) from exc

            # PostgreSQL can seek to this pair directly in the composite index.
            queryset = queryset.filter(RawSQL(
                '("emails"."imported_at", "emails"."gmail_id") < (%s, %s)',
                (datetime.fromisoformat(imported_at), gmail_id),
                output_field=BooleanField(),
            ))
        else:
            revision = latest_revision() if at is None else decode_revision(at, "at", account)

        # Read a stable set of imports as the person navigates, even if a
        # later commit carries an imported_at older than the current page.
        queryset = queryset.filter(Q(import_revision__isnull=True) | Q(import_revision__lte=revision))
        items = list(queryset[:self.page_size + 1])
        next_cursor = None
        if len(items) > self.page_size:
            items.pop()
            last = items[-1]
            next_cursor = signing.dumps(
                [revision, last.imported_at.isoformat(), last.gmail_id], salt=cursor_salt,
            )

        return Response({
            "items": self.get_serializer(items, many=True).data,
            "next_cursor": next_cursor,
            "change_cursor": encode_revision(revision, account),
        })


class EmailChangesView(AccountEmailView, generics.GenericAPIView):
    serializer_class = EmailMetadataSerializer
    page_size = 5

    def get_serializer_class(self):
        if self.request.query_params.get("include_updates") == "true":
            return EmailChangeMetadataSerializer
        return super().get_serializer_class()

    def get(self, request):
        account = request.email_account
        after = decode_revision(request.query_params.get("cursor", ""), "cursor", account)
        until = request.query_params.get("until")
        upper = latest_revision() if until is None else decode_revision(until, "until", account)
        if after > upper:
            raise ValidationError({"until": "O limite precede o cursor de importação."})

        category = request.query_params.get("category")
        include_updates = request.query_params.get("include_updates", "false")
        if include_updates not in ("true", "false"):
            raise ValidationError({"include_updates": "Use true ou false."})
        revision_field = "update_revision" if include_updates == "true" else "import_revision"
        visible = self.account_emails() if include_updates == "true" else self.get_queryset()
        queryset = visible.only(*self.get_serializer_class().model_fields, revision_field).filter(**{
            f"{revision_field}__gt": after, f"{revision_field}__lte": upper,
        }).order_by(revision_field)
        if category is not None:
            queryset = queryset.annotate(
                effective_category=Coalesce("manual_category", "category", Value(""), output_field=TextField()),
            )

        items = list(queryset[:self.page_size + 1])
        has_more = len(items) > self.page_size
        if has_more:
            items.pop()
        next_revision = getattr(items[-1], revision_field) if has_more else upper
        # Filter only this bounded slice so a sparse category cannot force
        # a scan through the entire revision range.
        if category is not None:
            items = [item for item in items if item.effective_category == category]

        return Response({
            "items": self.get_serializer(items, many=True).data,
            "change_cursor": encode_revision(next_revision, account),
            "until_cursor": encode_revision(upper, account) if has_more else None,
        })


class EmailRetriaveDestroyView(AccountEmailView, generics.RetrieveDestroyAPIView):

    serializer_class = EmailSerializers

class EmailTrashView(AccountEmailView, generics.GenericAPIView):
    serializer_class = EmailTrashSerializer

    @transaction.atomic
    def patch(self, request, pk):
        if request.email_account is None:
            raise PermissionDenied()
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        queryset = self.account_emails().filter(pk=pk)
        email = generics.get_object_or_404(
            queryset.select_for_update().only("trash_pending_at", "trashed_at"),
        )
        if email.trashed_at is not None:
            return Response({"trashed": True})
        if serializer.validated_data["stage"] == "prepare":
            if email.trash_pending_at is None:
                queryset.update(trash_pending_at=Now())
            return Response({"trashed": False})
        if email.trash_pending_at is None:
            return Response(status=status.HTTP_409_CONFLICT)
        queryset.update(trash_pending_at=None, trashed_at=Now())
        return Response({"trashed": True})


class EmailConversationView(AccountEmailView, generics.GenericAPIView):
    max_messages = 5

    def get(self, request, pk):
        if request.email_account is None:
            raise PermissionDenied()
        queryset = self.get_queryset().only("gmail_id", "thread_id", "received_at")
        selected = generics.get_object_or_404(queryset, pk=pk)
        others = []
        if selected.thread_id:
            others = list(queryset.filter(thread_id=selected.thread_id).exclude(pk=pk).order_by(
                F("received_at").desc(nulls_last=True), "-gmail_id",
            )[:self.max_messages])
        messages = sorted(
            [selected, *others[:self.max_messages - 1]],
            key=lambda item: (item.received_at is None, item.received_at, item.gmail_id),
        )
        return Response({
            "gmail_ids": [item.gmail_id for item in messages],
            "partial": len(others) >= self.max_messages,
        })


class EmailManualCategoryView(AccountEmailView, generics.GenericAPIView):
    serializer_class = EmailManualCategorySerializer

    def patch(self, request, pk):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        category = serializer.validated_data["category"]
        email = self.get_queryset().filter(pk=pk)
        updated = email.filter(
            Q(category__isnull=True) | Q(category=""), manual_category__isnull=True,
        ).update(manual_category=category)
        if updated or email.filter(manual_category=category).exists():
            return Response(status=status.HTTP_204_NO_CONTENT)
        if not email.exists():
            raise NotFound()
        return Response(
            {"detail": "O e-mail já tem uma categoria."},
            status=status.HTTP_409_CONFLICT,
        )


class EmailBoardColumnView(AccountEmailView, generics.GenericAPIView):
    serializer_class = EmailBoardColumnSerializer

    def patch(self, request, pk):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        column = serializer.validated_data["column"]
        email = self.get_queryset().filter(pk=pk)
        updated = email.filter(processing_status="COMPLETED").update(
            board_column=None if column == AUTOMATIC_COLUMN else column,
        )
        try:
            current = email.only(*EmailMetadataSerializer.model_fields).get()
        except Emails.DoesNotExist as exc:
            raise NotFound() from exc
        if not updated:
            return Response(
                {"detail": "Aguarde a conclusão da análise para mover o cartão."},
                status=status.HTTP_409_CONFLICT,
            )
        return Response(EmailMetadataSerializer(current).data)
