@Grapes([
        @Grab(group='ch.qos.logback', module='logback-classic', version='1.0.+'),
        @Grab(group='org.slf4j', module='log4j-over-slf4j', version='1.7.+'),
        @Grab(group='org.slf4j', module='jcl-over-slf4j', version='1.7.+'),
        @Grab(group='com.amazonaws',module='aws-java-sdk-route53',version='1.10.+'),
        @Grab(group='commons-net',module='commons-net',version='3.3')
])
import com.amazonaws.services.route53.AmazonRoute53
import com.amazonaws.services.route53.AmazonRoute53Client
import com.amazonaws.services.route53.model.Change
import com.amazonaws.services.route53.model.ChangeAction
import com.amazonaws.services.route53.model.ChangeBatch
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest
import com.amazonaws.services.route53.model.RRType
import com.amazonaws.services.route53.model.ResourceRecord
import com.amazonaws.services.route53.model.ResourceRecordSet
import groovy.util.logging.Slf4j
import org.apache.commons.net.util.SubnetUtils
import org.apache.commons.net.util.SubnetUtils.SubnetInfo

/**
 * Command line interface for publishing forward and/or reverse
 * lookup records in Route53 private hosted zones for use in
 * VPCs with custom domains.
 */
def cli = new CliBuilder(
        usage: 'groovy r53-default-zone [options]',
        width: 120
)
cli.with {
    f   longOpt: 'forward-zone',         required: false, args: 1, argName: 'zoneID', 'The ID of the forward lookup zone in Route53'
    r   longOpt: 'reverse-zone',         required: false, args: 1, argName: 'zoneID', 'The ID of the reverse lookup zone in Route53'
    d   longOpt: 'domain',               required: false, args: 1, argName: 'domain', 'The custom domain suffix'
    s   longOpt: 'subnet',               required: false, args: 1, argName: 'subnet', 'The CIDR block of the subnet whose records will be generated'
    x   longOpt: 'delete',               required: false,                             'Delete records instead of adding them'
    '?' longOpt: 'help',                 required: false,                             'Display usage information'
}
def opts = cli.parse(args)

// implementing our own required argument logic so we can show help without errors
if (opts.'?') {
    cli.usage()
    System.exit 0
}

def missing = []
if (!(opts.f || opts.r)) {
    missing << '[-f | -r]'
}
if (!opts.s) {
    missing << '-s'
}
if (!(opts.x || opts.d)) {
    missing << '[-x | -d]'
}
if (missing) {
    println "Missing required options: ${missing.join(', ')}"
    cli.usage()
    System.exit 1
}

String forwardZone = opts.f ? opts.f.trim() : ''
String reverseZone = opts.r ? opts.r.trim() : ''

SubnetInfo subnet
try {
    subnet = new SubnetUtils(opts.s).with {
        // include network and broadcast addresses to account for generation of records
        // for partial CIDR blocks
        inclusiveHostCount = true
        info
    }
} catch (IllegalArgumentException iae) {
    println "Invalid Subnet [${opts.s}]: Subnet must be specified as a CIDR block"
    System.exit 1
}

String domain = opts.d.trim().toLowerCase()
if (!(domain ==~ /^([a-z][-a-z0-9]*\.)+[a-z]{2,}$/)) {
    println "Invalid domain: ${domain}"
    return 1
}

new ZoneBuilder([
        forwardZone: forwardZone,
        reverseZone: reverseZone,
        domain: domain,
        subnet: subnet,
        op: opts.x ? Op.DELETE : Op.CREATE
]).generateDefaultRecords()

enum Op {
    CREATE(ChangeAction.CREATE),
    DELETE(ChangeAction.DELETE)

    final ChangeAction action

    private Op(final ChangeAction atn) {
        action = atn
    }
}

enum ZoneType {
    FORWARD(RRType.A),
    REVERSE(RRType.PTR)

    final RRType recordType

    private ZoneType(final RRType rrType) {
        recordType = rrType
    }
}

@Slf4j
class ZoneBuilder {
    /** The maximum number of records in a Change request */
    static final int MAX_RECORDS = 1000
    /** The maximum number of combined characters in all Value sections of a Change request */
    static final int MAX_CHARACTERS = 32000
    /** The reverse lookup domain suffix */
    static final String REVERSE_LOOKUP_DOMAIN = 'in-addr.arpa'
    /** The default TTL (5 minutes), in seconds */
    static final long TTL = 300L

    final String forwardZone
    final String reverseZone
    final String domain
    final Op op
    final SubnetInfo subnet
    final AmazonRoute53 route53
    private int forwardRecords
    private int reverseRecords

    ZoneBuilder(Map args) {
        forwardZone = args['forwardZone']
        reverseZone = args['reverseZone']
        domain = args['domain']
        op = args['op']
        subnet = args['subnet']

        route53 = new AmazonRoute53Client()
    }

    void generateDefaultRecords() {
        List<ChangeSet> changeSets = []
        if (forwardZone) {
            changeSets << new ChangeSet(ZoneType.FORWARD)
        }
        if (reverseZone) {
            changeSets << new ChangeSet(ZoneType.REVERSE)
        }

        forwardRecords = 0
        reverseRecords = 0

        subnet.allAddresses.each { addr ->
            changeSets.each { it << addr }
        }
        changeSets.each { it.submit() }

        String ipRange = "${subnet.lowAddress} - ${subnet.highAddress}"
        if (forwardZone) {
            log.info "${ipRange} (FORWARD): ${op} ${forwardRecords} records processed"
        }
        if (reverseZone) {
            log.info "${ipRange} (REVERSE): ${op} ${reverseRecords} records processed"
        }
    }

    /**
     * Wrapper class that tracks the number of changes and the size of the
     * included value objects.
     */
    class ChangeSet {
        final ZoneType zone
        private List changes = []
        private int valueLength = 0
        private String startAddr
        private String endAddr

        ChangeSet(final ZoneType zt) {
            zone = zt
        }

        ChangeSet addChange(final String addr) {
            String name
            String value
            switch (zone) {
                case ZoneType.FORWARD:
                    name = "ip-${addr.replaceAll(/\./, '-')}.${domain}"
                    value = addr
                    break
                case ZoneType.REVERSE:
                    name = "${addr.split(/\./).reverse().join('.')}.${REVERSE_LOOKUP_DOMAIN}"
                    value = "ip-${addr.replaceAll(/\./, '-')}.${domain}"
                    break
            }
            assert name && value, "Unable to create ${zone} record for ${addr}"
            // if this ChangeSet has either the maximum number of records or if the total number
            // of characters in all Value elements for this ChangeSet plus the new Value exceeds
            // the maximum number of characters, submit this ChangeSet before adding the new record
            if (changes.size() == MAX_RECORDS || valueLength + value.length() > MAX_CHARACTERS) {
                log.debug "Submitting ${zone} Changes. { records: ${changes.size()}, length: ${valueLength}, newLength: ${valueLength + value.length()} }"
                submit()
            }
            if (!startAddr) {
                startAddr = addr
            }
            endAddr = addr
            valueLength += value.length()
            changes << new Change().withAction(op.action).withResourceRecordSet(new ResourceRecordSet().
                    withName(name).
                    withType(zone.recordType).
                    withTTL(TTL).
                    withResourceRecords(new ResourceRecord().withValue(value)))
            this
        }

        ChangeSet leftShift(final String addr) {
            addChange(addr)
        }

        void submit() {
            if (changes) {
                ChangeResourceRecordSetsRequest request = new ChangeResourceRecordSetsRequest().
                        withChangeBatch(new ChangeBatch().withChanges(changes))
                switch (zone) {
                    case ZoneType.FORWARD:
                        request.hostedZoneId = forwardZone
                        break
                    case ZoneType.REVERSE:
                        request.hostedZoneId = reverseZone
                        break
                }
                try {
                    route53.changeResourceRecordSets(request)
                    log.debug "${startAddr} - ${endAddr} (${zone}): ${op} ${changes.size()} records"
                    switch (zone) {
                        case ZoneType.FORWARD:
                            forwardRecords += changes.size()
                            break
                        case ZoneType.REVERSE:
                            reverseRecords += changes.size()
                            break
                    }
                    changes.clear()
                    startAddr = null
                    endAddr = null
                    valueLength = 0
                } catch (e) {
                    log.error "${startAddr} - ${endAddr} (${zone}): ${op} ${changes.size()} records -- ERROR: ${e}"
                    System.exit 1
                }
            }
        }
    }
}
