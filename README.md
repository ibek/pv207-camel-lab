## PV207 - Camel Lab

for integration beginners without the need for programming skills

### Prerequisites

- JBang
- Camel CLI

```bash
# Linux/Mac - Install JBang
curl -Ls https://sh.jbang.dev | bash -s - app setup

# Windows - Install JBang
iex "& { $(iwr https://ps.jbang.dev) } app setup"

# Install Camel CLI
jbang app install camel@apache/camel

# Check
camel version
# Output: Camel JBang version: 4.Y.Z
```

### 1. Store the last order

Platform HTTP `/order`
File - `?fileName=tmp/last-order.json`

```bash
camel init process-order.xml
camel run process-order.xml transformers/order_to_processed_order.jslt --dev

curl -H "Content-Type: application/json; charset=UTF-8" --data-binary @inputs/order.json http://0.0.0.0:8080/order
```

### 2. Route to standard or expedited delivery

Content-based router EIP (Choice)

- Standard condition: jsonpath `$[?(@.order_total &lt; 100)]` , `tmp/standard/last_order.json`
- Expedited: otherwise, `tmp/expedited/last_order.json`

```bash
curl -H "Content-Type: application/json; charset=UTF-8" --data-binary @inputs/order.json http://0.0.0.0:8080/order
curl -H "Content-Type: application/json; charset=UTF-8" --data-binary @inputs/order_premium.json http://0.0.0.0:8080/order
```

### 3. Filter orders with unknown payment method

Filter EIP

- jsonpath `$[?(@.payment_method !== 'Credit Card')]`
- `direct:other-payment`
- log "Unknown payment method"

```bash
curl -H "Content-Type: application/json; charset=UTF-8" --data-binary @inputs/order.json http://0.0.0.0:8080/order
curl -H "Content-Type: application/json; charset=UTF-8" --data-binary @inputs/order_unknown.json http://0.0.0.0:8080/order
```

### 4. Transform order to processed order json

JSLT Transformation

- transformers/order_to_processed_order.jslt

Message Translator EIP (Transform)

- JQ - add new delivery property in the routes of the previous Content-based router (`.delivery = "standard"`)
- Marshal back to json `<marshal><json /></marshal>`

```bash
curl -H "Content-Type: application/json; charset=UTF-8" --data-binary @inputs/order.json http://0.0.0.0:8080/order
```

```json
{
   "id":"899ac6c6-5939-4a91-8d0b-f0281e6b63ee",
   "customer":"Alex Smith",
   "items":{"Wireless Mouse":1,"Keyboard":1},
   "delivery":"standard"
}
```