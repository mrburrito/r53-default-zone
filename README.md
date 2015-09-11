# Route53 Default Records Generator

Several AWS services, such as Elastic MapReduce, rely on resolution of these hostnames to discover and communicate with the instances they create. If you configure a VPC with a custom domain name, these services fail to start because the hostnames do not resolve. The solution to this problem is creating private zones in Route53 to handle resolution of your internal domain name and reverse lookup of your private IP addresses and attaching them to your target VPC(s). This script can be used to populate those zones with forward and reverse Resource Records for every IP in your VPC's CIDR range.

## Example Configuration

Create a VPC with the CIDR range `10.10.0.0/22`. This allows you to create subnets with addresses ranging from `10.10.0.0` - `10.10.3.255`. If you modify the DHCP Options Set for that VPC to use the domain name `mydomain.local` instead of Amazon's default (for us-east-1) `ec2.internal`, DNS resolution for forward lookups of `ip-10-10-0-100` and
reverse lookups of `10.10.0.100` will both fail.

To fix this, add Route53 private Hosted Zones for `mydomain.local` and `10.10.in-addr.arpa` and attach them to your new VPC. Now, you can create `A` records in the `mydomain.local` zone that point `ip-10-10-0-100.mydomain.local` to `10.10.0.100` and `PTR` records in the `10.10.in-addr.arpa` zone to handle the reverse lookup. This process becomes tedious for more than a small handful of records and, since you don't know which addresses will be assigned, you need to add records for all possible addresses in your VPC.

## Prerequisites

-	Groovy 2.4.x (tested with Groovy 2.4.4, though earlier versions, including 2.2 and 2.3 may work)

## Usage

```
usage: groovy r53-default-zone.groovy [options]
 -?,--help                    Display usage information
 -d,--domain <domain>         The custom domain suffix
 -f,--forward-zone <zoneID>   The ID of the forward lookup zone in Route53
 -r,--reverse-zone <zoneID>   The ID of the reverse lookup zone in Route53
 -s,--subnet <subnet>         The CIDR block of the subnet whose records will be generated
 -x,--delete                  Delete records instead of adding them
```
 
At least one of the `-f` or `-r` options must be provided. The domain, `-d`, is only required when creating records. It can be omitted if `-x` is specified.
