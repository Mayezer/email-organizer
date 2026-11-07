from django.urls import path
from .sync_views import EmailSyncDetailView, EmailSyncView
from .views import EmailBoardColumnView, EmailChangesView, EmailConversationView, EmailListCreateView, EmailManualCategoryView, EmailPageView, EmailRetriaveDestroyView, EmailTrashView


urlpatterns = [
    path('email-sync-jobs/', EmailSyncView.as_view(), name='email-sync-jobs'),
    path('email-sync-jobs/<uuid:pk>/', EmailSyncDetailView.as_view(), name='email-sync-job'),
    path('email-pages/', EmailPageView.as_view(), name='email-pages'),
    path('email-changes/', EmailChangesView.as_view(), name='email-changes'),
    path('emails/<str:pk>/manual-category/', EmailManualCategoryView.as_view(), name='email-manual-category'),
    path('emails/<str:pk>/board-column/', EmailBoardColumnView.as_view(), name='email-board-column'),
    path('emails/<str:pk>/conversation/', EmailConversationView.as_view(), name='email-conversation'),
    path('emails/<str:pk>/trash/', EmailTrashView.as_view(), name='email-trash'),

    path('emails/',
        EmailListCreateView.as_view(),
        name='emails'),

    path('emails/<str:pk>/',
        EmailRetriaveDestroyView.as_view(),
        name='email-retriave-destroy-view')
]
