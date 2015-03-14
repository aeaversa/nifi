package org.apache.nifi.processors.aws.s3;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.util.StandardValidators;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;


@Tags({"Amazon", "S3", "AWS", "Get"})
@CapabilityDescription("Retrieves the contents of an S3 Object and writes it to the content of a FlowFile")
public class GetS3Object extends AbstractS3Processor {

    public static final PropertyDescriptor VERSION_ID = new PropertyDescriptor.Builder()
        .name("Version")
        .description("The Version of the Object to download")
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .expressionLanguageSupported(true)
        .required(false)
        .build();
    
    public static final PropertyDescriptor BYTE_RANGE_START = new PropertyDescriptor.Builder()
        .name("First Byte Index")
        .description("The 0-based index of the first byte to download. If specified, the first N bytes will be skipped, where N is the value of this property. If this value is greater than the size of the object, the FlowFile will be routed to failure.")
        .required(false)
        .expressionLanguageSupported(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();
    public static final PropertyDescriptor BYTE_RANGE_END = new PropertyDescriptor.Builder()
        .name("Last Byte Index")
        .description("The 0-based index of the last byte to download. If specified, last N bytes will be skipped, where N is the size of the object minus the value of this property. If the value is greater than the size of the object, the content will be downloaded to the end of the object.")
        .required(false)
        .expressionLanguageSupported(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();
    
    
    public static final List<PropertyDescriptor> properties = Collections.unmodifiableList(
            Arrays.asList(BUCKET, KEY, REGION, ACCESS_KEY, SECRET_KEY, CREDENTAILS_FILE, TIMEOUT, VERSION_ID,
                    BYTE_RANGE_START, BYTE_RANGE_END) );

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }
    
    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        FlowFile flowFile = session.get();
        if ( flowFile == null ) {
            return;
        }

        final long startNanos = System.nanoTime();
        final String bucket = context.getProperty(BUCKET).evaluateAttributeExpressions(flowFile).getValue();
        final String key = context.getProperty(KEY).evaluateAttributeExpressions(flowFile).getValue();
        final String versionId = context.getProperty(VERSION_ID).evaluateAttributeExpressions(flowFile).getValue();
        
        final AmazonS3 client = getClient();
        final GetObjectRequest request;
        if ( versionId == null ) {
            request = new GetObjectRequest(bucket, key);
        } else {
            request = new GetObjectRequest(bucket, key, versionId);
        }

        final Long byteRangeStart;
        final Long byteRangeEnd;
        try {
            final PropertyValue startVal = context.getProperty(BYTE_RANGE_START).evaluateAttributeExpressions(flowFile);
            byteRangeStart = startVal.isSet() ? startVal.asLong() : 0L;
            
            final PropertyValue endVal = context.getProperty(BYTE_RANGE_END).evaluateAttributeExpressions(flowFile);
            byteRangeEnd = endVal.isSet() ? endVal.asLong() : Long.MAX_VALUE;
        } catch (final NumberFormatException nfe) {
            getLogger().error("Failed to determine byte range for download for {} due to {}", new Object[] {flowFile, nfe});
            session.transfer(flowFile, REL_FAILURE);
            return;
        }
        
        if ( byteRangeStart != null && byteRangeEnd != null ) {
            if ( byteRangeEnd.longValue() < byteRangeStart.longValue() ) {
                getLogger().error("Failed to download object from S3 for {} because Start Byte Range is {} and End Byte Range is {}, which is less", new Object[] {flowFile, byteRangeStart, byteRangeEnd});
                session.transfer(flowFile, REL_FAILURE);
                return;
            }
            
            request.setRange(byteRangeStart.longValue(), byteRangeEnd.longValue());
        }
        
        final Map<String, String> attributes = new HashMap<>();
        try (final S3Object s3Object = client.getObject(request)) {
            flowFile = session.importFrom(s3Object.getObjectContent(), flowFile);
            attributes.put("s3.bucket", s3Object.getBucketName());
            
            final ObjectMetadata metadata = s3Object.getObjectMetadata();
            if ( metadata.getContentDisposition() != null ) {
                final String fullyQualified = metadata.getContentDisposition();
                final int lastSlash = fullyQualified.lastIndexOf("/");
                if ( lastSlash > -1 && lastSlash < fullyQualified.length() - 1 ) {
                    attributes.put(CoreAttributes.PATH.key(), fullyQualified.substring(0, lastSlash));
                    attributes.put(CoreAttributes.ABSOLUTE_PATH.key(), fullyQualified);
                    attributes.put(CoreAttributes.FILENAME.key(), fullyQualified.substring(lastSlash + 1));
                } else {
                    attributes.put(CoreAttributes.FILENAME.key(), metadata.getContentDisposition());
                }
            }
            if (metadata.getContentMD5() != null ) {
                attributes.put("hash.value", metadata.getContentMD5());
                attributes.put("hash.algorithm", "MD5");
            }
            if ( metadata.getContentType() != null ) {
                attributes.put(CoreAttributes.MIME_TYPE.key(), metadata.getContentType());
            }
            if ( metadata.getETag() != null ) {
                attributes.put("s3.etag", metadata.getETag());
            }
            if ( metadata.getExpirationTime() != null ) {
                attributes.put("s3.expirationTime", String.valueOf(metadata.getExpirationTime().getTime()));
            }
            if ( metadata.getExpirationTimeRuleId() != null ) {
                attributes.put("s3.expirationTimeRuleId", metadata.getExpirationTimeRuleId());
            }
            if ( metadata.getUserMetadata() != null ) {
                attributes.putAll(metadata.getUserMetadata());
            }
            if ( metadata.getVersionId() != null ) {
                attributes.put("s3.version", metadata.getVersionId());
            }
        } catch (final IOException | AmazonClientException ioe) {
            getLogger().error("Failed to retrieve S3 Object for {}; routing to failure", new Object[] {flowFile, ioe});
            session.transfer(flowFile, REL_FAILURE);
            return;
        }

        if ( !attributes.isEmpty() ) {
            flowFile = session.putAllAttributes(flowFile, attributes);
        }

        session.transfer(flowFile, REL_SUCCESS);
        final long transferMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        getLogger().info("Successfully retrieved S3 Object for {} in {} millis; routing to success", new Object[] {flowFile, transferMillis});
        session.getProvenanceReporter().receive(flowFile, "http://" + bucket + ".amazonaws.com/" + key, transferMillis);
    }

    
}
