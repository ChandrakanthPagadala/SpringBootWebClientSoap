package webclient.soap.encoding;

import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.CodecException;
import org.springframework.core.codec.Encoder;
import org.springframework.core.codec.EncodingException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.PooledDataBuffer;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.util.ClassUtils;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.WebServiceMessageFactory;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.support.DefaultStrategiesHelper;
import org.springframework.ws.support.MarshallingUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.MarshalException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Jaxb2SoapEncoder implements Encoder<Object> {

    private final ConcurrentMap<Class<?>, JAXBContext> jaxbContexts = new ConcurrentHashMap<>(64);

    @Override
    public boolean canEncode(ResolvableType elementType, MimeType mimeType) {
        Class<?> outputClass = elementType.toClass();
        return (outputClass.isAnnotationPresent(XmlRootElement.class) ||
                    outputClass.isAnnotationPresent(XmlType.class));

    }

    @Override
    public Flux<DataBuffer> encode(Publisher<?> inputStream, DataBufferFactory bufferFactory, ResolvableType elementType, MimeType mimeType, Map<String, Object> hints) {
        return Flux.from(inputStream)
                .take(1)
                .concatMap(value -> encode(value, bufferFactory, elementType, mimeType, hints))
                .doOnDiscard(PooledDataBuffer.class, PooledDataBuffer::release);
    }

    @Override
    public List<MimeType> getEncodableMimeTypes() {
        return Arrays.asList( MimeTypeUtils.TEXT_XML );
    }



    private Flux<DataBuffer> encode(Object value ,
                                    DataBufferFactory bufferFactory,
                                    ResolvableType type,
                                    MimeType mimeType,
                                    Map<String, Object> hints){

        return Mono.fromCallable(() -> {
            boolean release = true;
            DataBuffer buffer = bufferFactory.allocateBuffer(1024);
            try {
                OutputStream outputStream = buffer.asOutputStream();
                Class<?> clazz = ClassUtils.getUserClass(value);
                Marshaller marshaller = initMarshaller(clazz);

                DefaultStrategiesHelper helper = new DefaultStrategiesHelper(WebServiceTemplate.class);
                WebServiceMessageFactory messageFactory = helper.getDefaultStrategy(WebServiceMessageFactory.class);
                WebServiceMessage message = messageFactory.createWebServiceMessage();

                marshaller.marshal(value, message.getPayloadResult());
                message.writeTo(outputStream);

                release = false;
                return buffer;
            }
            catch (MarshalException ex) {
                throw new EncodingException(
                        "Could not marshal " + value.getClass() + " to XML", ex);
            }
            catch (JAXBException ex) {
                throw new CodecException("Invalid JAXB configuration", ex);
            }
            finally {
                if (release) {
                    DataBufferUtils.release(buffer);
                }
            }
        }).flux();
    }


    private Marshaller initMarshaller(Class<?> clazz) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance(clazz);
        Marshaller marshaller = jaxbContext.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());
        return marshaller;
    }
}
