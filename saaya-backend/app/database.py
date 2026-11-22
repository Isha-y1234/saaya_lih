from fastapi import Request
from pymongo.collection import Collection


def get_contacts_collection(request: Request) -> Collection:
    return request.app.state.database["trusted_contacts"]


def get_database(request: Request):
    return request.app.state.database
