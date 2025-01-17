/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jigasi.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.rayo.*;
import net.java.sip.communicator.util.*;
import org.dom4j.*;
import org.jitsi.jigasi.*;
import org.osgi.framework.*;
import org.xmpp.component.*;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Message;

/**
 * Experimental implementation of call control component that is capable of
 * utilizing Rayo XMPP protocol for the purpose of SIP gateway calls management.
 *
 * @author Pawel Domas
 */
public class CallControlComponent
    extends AbstractComponent
    implements CallsControl,
               ServiceListener
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(CallControlComponent.class);

    /**
     * Name of 'header' attribute that hold JVB room name.
     */
    public static final String ROOM_NAME_HEADER = "JvbRoomName";

    /**
     * Name of the domain on which this component is currently running.
     */
    private final String domain;

    /**
     * The {@link SipGateway} service which manages gateway sessions.
     */
    private SipGateway gateway;

    /**
     * FIXME: temporary to be removed/fixed
     */
    //private Map<SipGateway, String> hangupMap
      //  = new HashMap<SipGateway, String>();

    /**
     * Creates new isntance of <tt>CallControlComponent</tt>.
     * @param subdomain the name of component subdomain.
     * @param serverName the name of the server on which this component will run.
     */
    public CallControlComponent(String subdomain, String serverName)
    {
        this.domain = subdomain + "." + serverName;
    }

    /**
     * Initializes this component.
     */
    public void init()
    {
        this.gateway
            = ServiceUtils.getService(
                    JigasiBundleActivator.osgiContext,
                    SipGateway.class);

        gateway.setCallsControl(this);

        gateway.setXmppServerName(
            domain.substring(domain.indexOf(".") + 1));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String[] discoInfoFeatureNamespaces()
    {
        return
            new String[]
                {
                    "http://jitsi.org/protocol/jigasi",
                    "urn:xmpp:rayo:0"
                };
    }

    @Override
    public String getDescription()
    {
        return "Call control component";
    }

    @Override
    public String getName()
    {
        return "Call control";
    }

    /**
     * Initializes new outgoing call.
     * @param roomName the name of the MUC room that holds JVB conference call.
     * @param from source address(optional)
     * @param to destination call address/URI.
     * @return the call resource string that will identify newly created call.
     */
    String initNewCall(String roomName, String from, String to)
    {
        String callResource = generateNextCallResource();

        gateway.createOutgoingCall(to, roomName, callResource);

        return callResource;
    }

    private String generateNextCallResource()
    {
        //FIXME: fix resource generation and check if created resource
        // is already taken
        return Long.toHexString(System.currentTimeMillis()) + "@" + domain;
    }

    /**
     * Handles an <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>set</tt> which
     * represents a request.
     *
     * @param iq the <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>set</tt>
     * which represents the request to handle
     * @return an <tt>org.xmpp.packet.IQ</tt> stanza which represents the
     * response to the specified request or <tt>null</tt> to reply with
     * <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     * @see AbstractComponent#handleIQSet(IQ)
     */
    @Override
    public IQ handleIQSet(IQ iq)
        throws Exception
    {
        try
        {
            org.jivesoftware.smack.packet.IQ smackIq = IQUtils.convert(iq);

            if (smackIq instanceof RayoIqProvider.DialIq)
            {
                RayoIqProvider.DialIq dialIq = (RayoIqProvider.DialIq) smackIq;

                String from = dialIq.getSource();
                String to = dialIq.getDestination();

                String roomName = dialIq.getHeader(ROOM_NAME_HEADER);
                if (roomName == null)
                    throw new RuntimeException("No JvbRoomName header found");

                logger.info(
                    "Got dial request " + from + " -> " + to
                    + " room: " + roomName);

                String callResource = initNewCall(roomName, from, to);

                callResource = "xmpp:" + callResource;

                RayoIqProvider.RefIq ref
                    = RayoIqProvider.RefIq.createResult(smackIq, callResource);

                return IQUtils.convert(ref);
            }
            else if (smackIq instanceof RayoIqProvider.HangUp)
            {
                RayoIqProvider.HangUp hangUp
                    = (RayoIqProvider.HangUp) smackIq;

                String callUri = hangUp.getTo();
                String callResource = callUri;

                GatewaySession session = gateway.getSession(callResource);

                if (session == null)
                    throw new RuntimeException(
                        "No gateway for call: " + callResource);

                //hangupMap.put(gateway, smackIq.getFrom());

                session.hangUp();

                org.jivesoftware.smack.packet.IQ result
                    = org.jivesoftware.smack.packet.IQ.createResultIQ(smackIq);

                return IQUtils.convert(result);
            }
            else
            {
                return super.handleIQSet(iq);
            }
        }
        catch (Exception e)
        {
            logger.error(e, e);
            throw e;
        }
    }

    @Override
    public void callEnded(SipGateway gateway, String callResource)
    {
        // Send confirmation
        // FIXME: we've left the room already at this point
        /*EndExtension end = new EndExtension();
        end.setReason(
            new ReasonExtension(ReasonExtension.HANGUP));
        HeaderExtension header = new HeaderExtension();
        header.setName("uri");
        header.setValue("xmpp:" + gatewaySession.callResource);

        end.addChildExtension(header);

        gateway.sendPresenceExtension(end);*/
    }

    /**
     * Call resource currently has the form of e23gr547@callcontro.server.net.
     * This methods extract random call id part before '@' sign. In the example
     * above it is 'e23gr547'.
     * @param callResource the call resource/URI from which the call ID part
     *                     will be extracted.
     * @return extracted random call ID part from full call resource string.
     */
    @Override
    public String extractCallIdFromResource(String callResource)
    {
        return callResource.substring(0, callResource.indexOf("@"));
    }

    protected void sendPacketXml(String xmlToSend)
    {
        try
        {
            Document doc = DocumentHelper.parseText(xmlToSend);

            org.xmpp.packet.Message toSend
                = new Message(doc.getRootElement());

            send(toSend);
        }
        catch (DocumentException e)
        {
            logger.error(e, e);
        }
    }

    @Override
    public void serviceChanged(ServiceEvent serviceEvent)
    {
        if (serviceEvent.getType() != ServiceEvent.REGISTERED)
            return;

        ServiceReference ref = serviceEvent.getServiceReference();

        Object service = JigasiBundleActivator.osgiContext.getService(ref);

        if (!(service instanceof SipGateway))
            return;

        SipGateway gateway = (SipGateway) service;

        gateway.setCallsControl(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String allocateNewSession(SipGateway gateway)
    {
        return generateNextCallResource();
    }
}
