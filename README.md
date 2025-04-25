## Scindns

A light DNS server with subnet level resolution.

This project was born out of frustration with both Tailscale and Pi-Hole / Dnsmasq being unwilling to compromise on fixing `localise-queries` with a /32 sized subnet.

If you're running this for a Tailscale + Pi-Hole setup, move Pi-Hole to a different port and put this in front of Pi-Hole. Then setup your records for your Tailscale services in `config.json`.

## Setup

Download the standalone [binary](./udproxy.jar) and copy [config.json.example](./config.json.example) to `config.json` locally. The binary tries to find `config.json` relative to your current directory.

Edit the config to your liking:

 - `upstream` - the hostname of the upstream DNS server. If you're using Pi-Hole, it's highly recommended to make this your pi's address on your network instead of `localhost`.
 - `port` - The port to use when connecting to the upstream DNS server.
 - `records` - An array containing DNS records
   - `name` - The name of the domain this DNS record belongs to
   - `type`- The type of the DNS record
   - `resolutions` - An array containing resolutions
     - `network` - Your IPV4 network address in dotted decimal notation (192.168.0.0)
     - `mask` - The subnet mask associated with this network address.
     - `value` - The value to respond by when the network and mask matches the connection's address.

 For Tailscale, the network of `100.0.0.0` and `255.0.0.0` seems to work well.

## Building

Ideally, you should be able to just click the assemble Gradle task, which puts the built jar with dependencies in /build/libs