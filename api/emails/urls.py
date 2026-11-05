from django.urls import path
from .views import EmailListCreateView, EmailRetriaveDestroyView


urlpatterns = [
    path('emails/',
        EmailListCreateView.as_view(),
        name='emails'),

    path('emails/<str:pk>/',
        EmailRetriaveDestroyView.as_view(),
        name='email-retriave-destroy-view')
]
