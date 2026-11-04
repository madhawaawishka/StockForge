import urllib.request
import urllib.error
import json
import time
import uuid
import hmac
import hashlib
import base64

LOCAL_SECRET = "stockforge-local-development-only-hmac-key"
GATEWAY_URL = "http://localhost:8088"

def mint_token(user_id=None, admin=False):
    if not user_id:
        user_id = str(uuid.uuid4())
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": "stockforge-auth",
        "aud": ["stockforge-api"],
        "sub": user_id,
        "roles": ["ADMIN"] if admin else ["CUSTOMER"],
        "iat": now,
        "exp": now + 3600
    }
    def b64(d):
        return base64.urlsafe_b64encode(json.dumps(d, separators=(",", ":")).encode()).rstrip(b"=").decode("ascii")
    
    h_enc = b64(header)
    p_enc = b64(payload)
    sig = hmac.new(LOCAL_SECRET.encode(), f"{h_enc}.{p_enc}".encode(), hashlib.sha256).digest()
    s_enc = base64.urlsafe_b64encode(sig).rstrip(b"=").decode("ascii")
    return f"{h_enc}.{p_enc}.{s_enc}"

admin_token = mint_token(admin=True)

def api_post(path, data, token=None, headers=None):
    if headers is None:
        headers = {}
    headers["Content-Type"] = "application/json"
    headers["Accept"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{GATEWAY_URL}{path}", data=json.dumps(data).encode(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode()
            return resp.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        return e.code, json.loads(body) if body else {}
    except Exception as e:
        return 0, str(e)

def api_get(path, token=None, headers=None):
    if headers is None:
        headers = {}
    headers["Accept"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{GATEWAY_URL}{path}", headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode()
            return resp.status, json.loads(body) if body else {}, dict(resp.headers)
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        return e.code, json.loads(body) if body else {}, dict(e.headers)
    except Exception as e:
        return 0, str(e), {}

print("Populating traffic for Grafana & Kafka UI...")

# 1. Create 3 products
products = []
items = [
    ("SNEAKER-PRO-99", "Pro Edition Sneaker 99", "129.00", 250),
    ("KEYBOARD-RGB-60", "60% Mechanical Keyboard RGB", "89.50", 150),
    ("HOODIE-VINTAGE-01", "Vintage Heavyweight Hoodie", "65.00", 300)
]

for sku, name, amount, stock in items:
    status, res = api_post("/api/v1/admin/products", {
        "sku": f"{sku}-{int(time.time()) % 10000}",
        "name": name,
        "price": {"amount": amount, "currency": "USD"},
        "initialStock": stock
    }, token=admin_token)
    if status == 201:
        products.append(res["id"])
        print(f"Created product: {name} (ID: {res['id']})")

if not products:
    # fallback to listing existing
    s, cat, _ = api_get("/api/v1/products")
    if s == 200 and cat.get("items"):
        products = [p["id"] for p in cat["items"]]

print(f"Active products: {len(products)}")

# 2. Fire 30 reservations and checkouts
shopper_alice = mint_token(user_id="0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e6f")
shopper_bob = mint_token(user_id="0192f5c4-6b0d-7e21-8f43-1a2b3c4d5e99")

for i in range(25):
    pid = products[i % len(products)]
    token = shopper_alice if i % 2 == 0 else shopper_bob
    idem_res = f"res-{uuid.uuid4()}"
    
    # Check availability (generate cache hits and 304s)
    s_av, _, h_av = api_get(f"/api/v1/products/{pid}/availability")
    etag = h_av.get("ETag") or h_av.get("etag")
    if etag:
        api_get(f"/api/v1/products/{pid}/availability", headers={"If-None-Match": etag})
        
    # Reserve
    s_res, res_data = api_post("/api/v1/reservations", {
        "productId": pid,
        "quantity": 1
    }, token=token, headers={"Idempotency-Key": idem_res})
    
    if s_res == 201 and "id" in res_data:
        res_id = res_data["id"]
        
        # Test idempotent replay
        api_post("/api/v1/reservations", {
            "productId": pid,
            "quantity": 1
        }, token=token, headers={"Idempotency-Key": idem_res})
        
        # Place order
        # Every 4th order inject a payment failure for saga compensation
        sim = "FAILED" if i % 4 == 0 else None
        headers = {"Idempotency-Key": f"ord-{uuid.uuid4()}"}
        if sim:
            headers["X-Payment-Simulation"] = sim
            
        s_ord, ord_data = api_post("/api/v1/orders", {
            "reservationId": res_id
        }, token=token, headers=headers)
        
        if s_ord == 202 and "id" in ord_data:
            ord_id = ord_data["id"]
            time.sleep(0.05)
            api_get(f"/api/v1/orders/{ord_id}", token=token)

print("Traffic generation complete! Invariants and Kafka topics actively populated.")

