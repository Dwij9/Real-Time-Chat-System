// k6 load test for the WebSocket path.
//
//   k6 run --vus 200 --duration 60s scripts/load-test.js
//
// Each virtual user opens a socket, heartbeats on the same 10 second cadence a real client uses, and
// sends on a think-time interval. What this measures is the gateway's connection handling and the
// round trip from send to delivery — not the database, which sits behind Kafka and absorbs load at its
// own pace by design.

import ws from 'k6/ws';
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'localhost:8081';
const CONVERSATION = __ENV.CONVERSATION || 'dm:alice:bob';

const roundTrip = new Trend('message_round_trip_ms');
const delivered = new Counter('messages_delivered');

export const options = {
  thresholds: {
    // The number that matters: how long from pressing send to the message coming back sequenced.
    message_round_trip_ms: ['p(95)<250', 'p(99)<500'],
    ws_connecting: ['p(95)<1000'],
  },
};

export default function () {
  const userId = `load-${__VU}`;
  const token = http.get(`http://${GATEWAY}/dev/token?userId=${userId}`).json('token');
  const sentAt = {};

  const response = ws.connect(`ws://${GATEWAY}/ws?token=${token}&deviceId=k6`, {}, socket => {
    socket.on('open', () => {
      socket.setInterval(() => socket.send(JSON.stringify({ type: 'heartbeat' })), 10000);

      socket.setInterval(() => {
        const messageId = `${userId}-${Date.now()}`;
        sentAt[messageId] = Date.now();
        socket.send(JSON.stringify({
          type: 'send',
          messageId,
          conversationId: CONVERSATION,
          body: 'load test message',
          sentAt: Date.now(),
        }));
      }, 2000);
    });

    socket.on('message', raw => {
      const frame = JSON.parse(raw);
      if (frame.type === 'message' && sentAt[frame.messageId]) {
        roundTrip.add(Date.now() - sentAt[frame.messageId]);
        delivered.add(1);
        delete sentAt[frame.messageId];
      }
    });

    socket.setTimeout(() => socket.close(), 30000);
  });

  check(response, { 'socket opened': r => r && r.status === 101 });
}
