FROM python:3.11-slim

WORKDIR /app

RUN pip install --no-cache-dir uv

COPY pyproject.toml README.md ./
RUN uv pip install --system -e .

COPY src/ ./src/

ENV PORT=8000
EXPOSE 8000

CMD ["python", "src/api.py"]
