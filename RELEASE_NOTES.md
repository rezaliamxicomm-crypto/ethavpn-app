2.0.2: a second measurement of all lines ten seconds after connecting, so the first pick is corrected within seconds when the first, cold measurement read high; the three-minute rounds now vary a little in timing.

2.0.1: the lines are now measured against the service's own target on iPhone too (2.0.0 fell back to the old address), and a refreshed subscription no longer restarts the connection when only Cloudflare's hourly ECH configuration changed; a new user id from the service does reach the running connection.

No server list any more: the app shows the latency of the line it is on, and two buttons, "Test latency" and "Refresh". The lines are measured against the box itself instead of a third party, the results go to the box with the next fetch, a link on the hop's address opens the app at the tap, the link is fetched three ways at once (ECH first, the hop after a head start), and when no line answers the app says so, keeps measuring every minute and fetches fresh lines by itself.

Connecting is immediate: the app no longer tests every server before it connects. It connects on the best server it knows, measures all of them in the background and moves new connections to a clearly faster one by itself, without cutting what is open. A line that stops answering is left within seconds. Test latency never interrupts the connection.
