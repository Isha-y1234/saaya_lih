from pydantic import BaseModel, ConfigDict, Field


class DeviceTokenRemoval(BaseModel):
    model_config = ConfigDict(extra="forbid")
    token: str = Field(min_length=20, max_length=4096, pattern=r"^[A-Za-z0-9_:.-]+$")


class DeviceTokenRegistration(DeviceTokenRemoval):
    notificationsEnabled: bool = True


class NotificationLinkCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    contactId: str = Field(pattern=r"^[a-fA-F0-9]{24}$")
    recipientCode: str = Field(min_length=32, max_length=32, pattern=r"^[A-Za-z0-9_-]+$")
