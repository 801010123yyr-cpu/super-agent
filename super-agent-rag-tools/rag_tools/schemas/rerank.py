from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field


class RerankCandidate(BaseModel):
    id: str
    text: str
    metadata: dict[str, Any] = Field(default_factory=dict)


class RerankRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    query: str = ""
    candidates: list[RerankCandidate] = Field(default_factory=list)
    top_k: Annotated[int, Field(alias="topK", ge=0)] = 5


class RerankResult(BaseModel):
    id: str
    score: float
    rank: int


class RerankResponse(BaseModel):
    results: list[RerankResult] = Field(default_factory=list)
