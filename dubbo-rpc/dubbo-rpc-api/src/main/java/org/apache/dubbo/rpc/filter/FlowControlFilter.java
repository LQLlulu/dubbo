/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.flowcontrol.collector.ServerMetricsCollector;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ScopeModelAware;
import org.apache.dubbo.rpc.model.ScopeModelUtil;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.RpcUtils;

import static org.apache.dubbo.common.constants.CommonConstants.*;

@Activate(group = PROVIDER,value = "flowcontrol")
public class FlowControlFilter implements Filter, BaseFilter.Listener, ScopeModelAware {
    private static final String FLOW_CONTROL_FILTER_START_TIME = "flow_control_filter_start_time";
    private FlowControl flowControl;
    private ApplicationModel applicationModel;
    @Override
    public void setApplicationModel(ApplicationModel applicationModel) {
        this.applicationModel = applicationModel;
    }


    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        URL url = invoker.getUrl();
        String methodName = invocation.getMethodName();
        if(flowControl == null){
            flowControl = initFlowControl(invoker,invocation);
        }
        if (!flowControl.Begin()){
            throw  new RpcException(RpcException.LIMIT_EXCEEDED_EXCEPTION,"Failed to invoke method " + methodName + "in provider " + url + ", cause: The service using threads greater than FlowControl limited.The max concurrency is " + flowControl.getMaxConcurrency());
        }

        invocation.put(FLOW_CONTROL_FILTER_START_TIME,System.nanoTime() / 1000);
        try{
            return invoker.invoke(invocation);
        }catch (Throwable t){
            if(t instanceof RuntimeException){
                throw (RuntimeException)t;
            }else{
                throw new RpcException("unexpected exception when FlowControlFilter",t);
            }
        }
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        //RpcStatus.endCount(invoker.getUrl(),getRealMethodName(invoker,invocation),getElapsed(invocation),true);
        //ServerMetricsCollector.end(invoker.getUrl(),getRealMethodName(invoker,invocation),getElapsed(invocation), ServerMetricsCollector.defaultBucketNum,ServerMetricsCollector.defaultTimeWindowSeconds);
        flowControl.End(getElapsed(invocation));
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        if (t instanceof RpcException) {
            RpcException rpcException = (RpcException) t;
            if (rpcException.isLimitExceed()) {
                return;
            }
        }
        RpcStatus.endCount(invoker.getUrl(), getRealMethodName(invoker, invocation), getElapsed(invocation), false);
    }

    protected FlowControl initFlowControl(Invoker<?> invoker,Invocation invocation){
        ApplicationModel applicationModel = ScopeModelUtil.getApplicationModel(invocation.getModuleModel());
        FlowControl flowControl = applicationModel.getExtensionLoader(FlowControl.class).getExtension(invoker.getUrl().getMethodParameter(RpcUtils.getMethodName(invocation),FLOW_CONTROL_KEY,DEFAULT_FLOW_CONTROL));
        //FlowControl flowControl = applicationModel.getExtensionLoader(FlowControl.class).getDefaultExtension();
        return flowControl;
    }

    private long getElapsed(Invocation invocation) {
        Object beginTime = invocation.get(FLOW_CONTROL_FILTER_START_TIME);
        return beginTime != null ? System.nanoTime() / 1000 - (Long) beginTime : 0;
    }

    private String getRealMethodName(Invoker<?> invoker, Invocation invocation) {
        if ((invocation.getMethodName().equals($INVOKE) || invocation.getMethodName().equals($INVOKE_ASYNC))
            && invocation.getArguments() != null
            && invocation.getArguments().length == 3
            && !GenericService.class.isAssignableFrom(invoker.getInterface())) {
            return ((String) invocation.getArguments()[0]).trim();
        }
        return invocation.getMethodName();
    }
}
