"""Permanent public account identifiers, independent of Google profile changes."""
from pymongo import ReturnDocument


def ensure_saaya_id(users, user):
    if user.get("saayaId"):
        return user
    # The full Mongo ObjectId provides a unique, stable namespace without a
    # random-code collision or a dependency on phone/email verification.
    return users.find_one_and_update(
        {"_id": user["_id"], "saayaId": None},
        {"$set": {"saayaId": "SAAYA-" + str(user["_id"]).upper()}},
        return_document=ReturnDocument.AFTER,
    ) or users.find_one({"_id": user["_id"]})


def initialize_saaya_ids(database):
    users = database["users"]
    # The partial index allows old accounts to be backfilled while enforcing
    # uniqueness for every assigned ID, including concurrent app startups.
    users.create_index("saayaId", unique=True,
                       partialFilterExpression={"saayaId": {"$type": "string"}})
    for user in users.find({}):
        ensure_saaya_id(users, user)
