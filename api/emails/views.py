from datetime import datetime

from django.core import signing
from django.db.models import BooleanField, Q, TextField, Value
from django.db.models.expressions import RawSQL
from django.db.models.functions import Coalesce
from rest_framework import generics, status
from rest_framework.exceptions import NotFound, ValidationError
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response

from emails.import_cursor import decode_revision, encode_revision, latest_revision
from emails.models import Emails
from emails.permissions import EmailPermissionClass
from emails.serializers import EmailManualCategorySerializer, EmailMetadataSerializer, EmailSerializers


class EmailListCreateView(generics.ListCreateAPIView):

    permission_classes = (IsAuthenticated, EmailPermissionClass)
    queryset = Emails.objects.all()
    serializer_class = EmailSerializers

    def get_queryset(self):
        queryset = Emails.objects.all()
        category = self.request.query_params.get("category")
        if category != None:
            queryset = queryset.filter(category = category)
        return queryset


class EmailPageView(generics.GenericAPIView):
    permission_classes = (IsAuthenticated, EmailPermissionClass)
    serializer_class = EmailMetadataSerializer
    page_size = 5
    cursor_salt = "emails.page.v2"

    def get(self, request):
        queryset = Emails.objects.only(*self.serializer_class.model_fields).order_by(
            "-imported_at", "-gmail_id",
        )
        category = request.query_params.get("category")
        cursor_salt = self.cursor_salt
        if category is not None:
            queryset = queryset.alias(
                category_filter=Coalesce("manual_category", "category", Value(""), output_field=TextField()),
            ).filter(category_filter=category)
            cursor_salt = f"{cursor_salt}.category:{category}"

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
            revision = latest_revision() if at is None else decode_revision(at, "at")

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
            "change_cursor": encode_revision(revision),
        })


class EmailChangesView(generics.GenericAPIView):
    permission_classes = (IsAuthenticated, EmailPermissionClass)
    serializer_class = EmailMetadataSerializer
    page_size = 5

    def get(self, request):
        after = decode_revision(request.query_params.get("cursor", ""), "cursor")
        until = request.query_params.get("until")
        upper = latest_revision() if until is None else decode_revision(until, "until")
        if after > upper:
            raise ValidationError({"until": "O limite precede o cursor de importação."})

        category = request.query_params.get("category")
        include_updates = request.query_params.get("include_updates", "false")
        if include_updates not in ("true", "false"):
            raise ValidationError({"include_updates": "Use true ou false."})
        revision_field = "update_revision" if include_updates == "true" else "import_revision"
        queryset = Emails.objects.only(*self.serializer_class.model_fields, revision_field).filter(**{
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
            "change_cursor": encode_revision(next_revision),
            "until_cursor": encode_revision(upper) if has_more else None,
        })


class EmailRetriaveDestroyView(generics.RetrieveDestroyAPIView):

    permission_classes = (IsAuthenticated, EmailPermissionClass)
    queryset = Emails.objects.all()
    serializer_class = EmailSerializers


class EmailManualCategoryView(generics.GenericAPIView):
    permission_classes = (IsAuthenticated, EmailPermissionClass)
    serializer_class = EmailManualCategorySerializer

    def patch(self, request, pk):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        category = serializer.validated_data["category"]
        email = Emails.objects.filter(pk=pk)
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
