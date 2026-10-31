import struct, subprocess, sys, json

p = subprocess.Popen([sys.executable, "worker.py"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, cwd="D:/Projects/Auloud/scribe")
def send(obj):
    raw=json.dumps(obj).encode()
    p.stdin.write(struct.pack("<I",len(raw))+raw); p.stdin.flush()
def recv():
    h=p.stdout.read(4); n=struct.unpack("<I",h)[0]; return json.loads(p.stdout.read(n))
send({"v":1,"id":"h1","protocol_version":1})
print(recv())
send({"v":1,"id":"s1","batch_id":"b1","items":[{"index":0,"text":"hi","voice":"v","speed":1.0,"cache_key":"k"}]})
print(recv())
send({"v":1,"id":"x1"})
p.stdin.close()
p.wait(timeout=5)
print("exit", p.returncode)
