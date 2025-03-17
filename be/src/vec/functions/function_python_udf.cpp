// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "vec/functions/function_python_udf.h"

#include <memory>
#include <string>
#include <vector>

#include "runtime/user_function_cache.h"

#include "vec/columns/column.h"
#include "vec/common/assert_cast.h"
#include "vec/core/block.h"


namespace doris::vectorized {
PythonFunctionCall::PythonFunctionCall(const TFunction& fn, const DataTypes& argument_types,
                                   const DataTypePtr& return_type)
        : fn_(fn), _argument_types(argument_types), _return_type(return_type) {}

Status PythonFunctionCall::open(FunctionContext* context, FunctionContext::FunctionStateScope scope) {

    LOG(INFO) << "zhangmao " << __PRETTY_FUNCTION__;
    LOG(INFO) << "scope = " << scope << ", fn_.name = " << fn_.name;
    // TODO
    // 1. Get Python UDF executor ENV

    // 2. init the PyArrowContext.
    if (scope == FunctionContext::FunctionStateScope::THREAD_LOCAL) {
        SCOPED_TIMER(context->get_udf_execute_timer());
        std::shared_ptr<PyArrowContext> pyArrow_ctx = std::make_shared<PyArrowContext>();
        context->set_function_state(FunctionContext::THREAD_LOCAL, pyArrow_ctx);
        pyArrow_ctx->open_successes = true;
    }
    return Status::OK();
}

Status PythonFunctionCall::execute_impl(FunctionContext* context, Block& block,
                                      const ColumnNumbers& arguments, uint32_t result,
                                      size_t num_rows) const {

    LOG(INFO) << "zhangmao " << __PRETTY_FUNCTION__;

    return Status::OK();
}

Status PythonFunctionCall::close(FunctionContext* context,
                               FunctionContext::FunctionStateScope scope) {

    return Status::OK();
}
} // namespace doris::vectorized
