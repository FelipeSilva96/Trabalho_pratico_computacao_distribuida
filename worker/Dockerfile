FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY proto ./proto
COPY app.py .
COPY lamport.py .
COPY classifier.py .

RUN python -m grpc_tools.protoc \
    -I./proto \
    --python_out=. \
    --grpc_python_out=. \
    ./proto/classifier.proto

EXPOSE 50051

CMD ["python", "app.py"]
