FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY rag-tools.yaml ./rag-tools.yaml
COPY prompt ./prompt
COPY rag_tools ./rag_tools

EXPOSE 18080

CMD ["uvicorn", "rag_tools.main:app", "--host", "0.0.0.0", "--port", "18080"]
