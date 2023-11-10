I took ToyVPN as a template to create IP packet interceptor, which will do packet manipulations to achieve DPI bypass without root access.

The project is blocked because I need to understand how to implement packet interception like [pcapdroid](https://github.com/emanuele-f/PCAPdroid).
```
            while (!Thread.currentThread().isInterrupted()) {
                // Read the outgoing packet from the input stream (Virtual Interface).
                int length = ifaceIn.read(packet.array());

                if (length > 0) { // TODO: Is it even possible for a packet to be 0 bytes??
                    // Here I need to implement the packet manipulations for DPI bypass
                    ifaceOut.write(packet.array(), 0, length);
                    packet.clear();
                }
            }
``` 
I get IP packets into the 'packet' variable. The problem is that VPN must send those packets to the server via socket connection, which I must avoid because the whole purpose of the project is to avoid any server usage. Right now it just puts packets that are going out into incoming, which is broken and just does not let you use the internet.

To send IP packets directly, you must use raw sockets which require root access.

I need to dig into apps like pcapdroid to understand how they avoid making socket connections to remote server.
