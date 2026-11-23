FROM python:3.11-slim

WORKDIR /app

RUN pip install --no-cache-dir uv

COPY pyproject.toml README.md ./
COPY src/ ./src/
RUN uv pip install --system .

ENV PORT=8000
EXPOSE 8000

CMD ["python", "src/api.py"]
