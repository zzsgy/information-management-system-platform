package com.company.platform;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 直接调用所有平台控制器的真实响应包装，覆盖共享函数之外遗留的手写包装。 */
class PlatformResponseContractTest {
    @TestFactory Stream<DynamicTest> controllerSuccessResponsesUseTheSameContract()throws Exception{
        var scan=new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var tests=new ArrayList<DynamicTest>();
        for(var bean:scan.findCandidateComponents("com.company.platform")){
            Class<?> type=Class.forName(bean.getBeanClassName());
            for(Method wrapper:type.getDeclaredMethods()){
                if(!wrapper.getName().equals("ok")||!Arrays.equals(wrapper.getParameterTypes(),new Class<?>[]{Object.class,HttpServletRequest.class}))continue;
                tests.add(DynamicTest.dynamicTest(type.getSimpleName(),()->{
                    Object controller=null;
                    if(!Modifier.isStatic(wrapper.getModifiers())){
                        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
                        controller=constructor.newInstance(new Object[constructor.getParameterCount()]);
                    }
                    wrapper.setAccessible(true);UUID id=UUID.randomUUID();
                    var request=new MockHttpServletRequest();request.setAttribute(HttpBoundary.REQUEST,id);
                    var data=Map.of("items",List.of(Map.of("id","response-contract")),"total",1);
                    var result=(Map<?,?>)wrapper.invoke(controller,data,request);
                    assertEquals("OK",result.get("code"));
                    assertInstanceOf(String.class,result.get("message"));assertFalse(result.get("message").toString().isBlank());
                    assertEquals(data,result.get("data"));assertEquals(id,result.get("requestId"));
                    assertDoesNotThrow(()->Instant.parse(result.get("timestamp").toString()));
                }));
            }
        }
        assertTrue(tests.size()>=20,"扫描必须涵盖平台各模块控制器，不能只测统一包装函数");return tests.stream();
    }
}
