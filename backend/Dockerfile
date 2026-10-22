FROM python:3.12.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PORT=8000
WORKDIR /service
COPY requirements.lock.txt ./
RUN pip install --no-cache-dir -r requirements.lock.txt && \
    groupadd --gid 10001 tamaq && useradd --uid 10001 --gid tamaq --no-create-home tamaq && \
    mkdir -p /data && chown tamaq:tamaq /data
COPY --chown=tamaq:tamaq tamaq ./tamaq
COPY --chown=tamaq:tamaq evaluation ./evaluation
COPY --chown=tamaq:tamaq catalog_seed ./catalog_seed
# Mounted disks may be root-owned. Launcher initializes /data and the configured
# private avatar mount, then drops groups/UID before serving requests.
USER 0:0
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD python -c "import os,urllib.request; urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('PORT','8000')+'/health',timeout=3)"
CMD ["python", "-m", "tamaq.server"]
