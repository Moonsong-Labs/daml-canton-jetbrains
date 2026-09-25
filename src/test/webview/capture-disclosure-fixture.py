"""Regenerate the HTML fixture from the pinned Daml Script language server.

Run with the required DPM SDK and Java available on PATH. Uses a temporary copy
of the project and a private local script-service process; no ledger is contacted.
"""
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import time
from urllib.parse import quote

RESOURCES = Path(__file__).resolve().parents[1] / 'resources' / 'webview'
SCRIPT_NAME = 'disclosureFixture'
DOCUMENT_OPEN = 'textDocument/didOpen'
RESULT_METHOD = 'daml/virtualResource/didChange'


def capture():
    with tempfile.TemporaryDirectory(prefix='disclosure-fixture-') as directory:
        project = Path(directory)
        shutil.copytree(RESOURCES / 'disclosure-project', project, dirs_exist_ok=True)
        source = project / 'daml' / 'Main.daml'
        with tempfile.TemporaryFile() as stderr:
            process = subprocess.Popen(
                ['dpm', 'damlc', 'ide', '--optOutTelemetry'], cwd=project,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr,
            )
            messages = queue.Queue()

            def read_messages():
                try:
                    while True:
                        headers = {}
                        while True:
                            line = process.stdout.readline()
                            if not line:
                                return
                            if line == b'\r\n':
                                break
                            key, value = line.decode().split(':', 1)
                            headers[key.lower()] = value.strip()
                        messages.put(json.loads(process.stdout.read(int(headers['content-length']))))
                finally:
                    messages.put(None)

            threading.Thread(target=read_messages, daemon=True).start()

            def send(method, params, request_id=None):
                message = {'jsonrpc': '2.0', 'method': method, 'params': params}
                if request_id is not None:
                    message['id'] = request_id
                data = json.dumps(message).encode()
                process.stdin.write(f'Content-Length: {len(data)}\r\n\r\n'.encode() + data)
                process.stdin.flush()

            deadline = time.monotonic() + 120

            def receive():
                message = messages.get(timeout=max(0, deadline - time.monotonic()))
                if message is None:
                    raise RuntimeError('Daml language server exited before returning the fixture')
                return message

            try:
                send('initialize', {'processId': os.getpid(), 'rootUri': project.as_uri(), 'capabilities': {}}, 1)
                while receive().get('id') != 1:
                    pass
                send('initialized', {})
                send(DOCUMENT_OPEN, {'textDocument': {
                    'uri': source.as_uri(), 'languageId': 'daml', 'version': 0, 'text': source.read_text(),
                }})
                uri = 'daml://compiler?file=' + quote(str(source), safe='') + '&top-level-decl=' + SCRIPT_NAME
                send(DOCUMENT_OPEN, {'textDocument': {'uri': uri, 'languageId': '', 'version': 0, 'text': ''}})
                while True:
                    message = receive()
                    if message.get('method') != RESULT_METHOD:
                        continue
                    result = message['params']['contents']
                    if 'Return value:' not in result or '<table' not in result:
                        raise RuntimeError('Script did not return a successful table result: ' + result[-2000:])
                    # Retain actual server markup while removing machine-specific source paths.
                    result = result.replace(str(source), 'daml/Main.daml')
                    result = result.replace(quote(str(source), safe=''), quote('daml/Main.daml', safe=''))
                    output = RESOURCES / 'fixtures' / 'disclosure-real.html'
                    output.write_text('\n'.join(line.rstrip() for line in result.splitlines()) + '\n')
                    print('Captured', output.name)
                    break
            except Exception:
                stderr.seek(0)
                print(stderr.read().decode(errors='replace')[-4000:])
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == '__main__':
    capture()
