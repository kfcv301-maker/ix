package com.admin.config;

import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class NodeObservationBodyFilterTest {
    @Test void rejectsLargeFixedAndChunkedBodyBeforeControllerRuns() throws Exception {
        for(boolean chunked:new boolean[]{false,true}) {
            var raw=new MockHttpServletRequest("POST","/api/v1/node/address-observation");raw.setContent(new byte[2049]);
            HttpServletRequest request=chunked?new HttpServletRequestWrapper(raw) {
                @Override public long getContentLengthLong() { return -1; }
                @Override public int getContentLength() { return -1; }
            }:raw;
            var response=new MockHttpServletResponse();var chain=new MockFilterChain();
            new NodeObservationBodyFilter().doFilter(request,response,chain);
            assertEquals(413,response.getStatus());assertNull(chain.getRequest());
        }
    }
    @Test void smallBodyReachesJsonControllerUnchangedAndOtherRoutesAreUntouched() throws Exception {
        byte[] body="{\"ip\":\"8.8.4.4\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request=new MockHttpServletRequest("POST","/api/v1/node/address-observation");request.setContent(body);
        new NodeObservationBodyFilter().doFilter(request,new MockHttpServletResponse(),(req,res)->assertArrayEquals(body,req.getInputStream().readAllBytes()));
        var unrelated=new MockHttpServletRequest("POST","/api/v1/node/update");unrelated.setContent(new byte[2049]);
        new NodeObservationBodyFilter().doFilter(unrelated,new MockHttpServletResponse(),(req,res)->assertSame(unrelated,req));
    }
}
