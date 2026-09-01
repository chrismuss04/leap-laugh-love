# leap-laugh-love

## Team Members
1. Team Lead - Chris Musselman
2. Angular Developer - Nikhil Akula
3. Developer - Yahia Elsaad
4. Developer - Lauren Sanday
5. Developer - Elisa Paul
   
## Branching Strategy
We are using the Trunk branching strategy because it best fits our development strategy and schedule.

## API  (View Order & Fill History story)

### `GET /api/orders/history`

Returns a client's order history, paginated and sorted chronologically (newest first, with `orderId` descending as a tie-break for identical timestamps).

**Query parameters**

| Name       | Required | Default | Notes                                              |
|------------|----------|---------|-----------------------------------------------------|
| `clientId` | Yes      | —       | UUID of the client. *Temporary stand-in until authentication resolves this from the session/token.* |
| `page`     | No       | `0`     | Zero-based page index. Must be `>= 0`.               |
| `size`     | No       | `20`    | Page size. Must be between `1` and `100`.            |

**Responses**

- `200 OK` — a Spring Data `Page<OrderHistoryItem>` body. Clients with no matching orders (or an unrecognized `clientId`) get an empty `content` array and `totalElements: 0`, not an error.
- `400 Bad Request` — `page` is negative, or `size` is outside `1..100`.

**`OrderHistoryItem` fields**: `orderId`, `symbol`, `side`, `quantity`, `status`, `submittedAt`, `filledAt`.

