/**
 * 将 Core 的受治理工具目录和调用接口适配为 MCP/HTTP 能力。
 *
 * <p>本包不直接执行业务写入；身份、权限、幂等和工具审计最终仍由 Core 判定。</p>
 * <p>SDK 回调固定使用发布时的工具契约版本。HTTP 调用方应将目录的 contractVersion
 * 作为 expectedVersion 提交；缺失仅表示版本 1，不会自动升级到当前目录版本。</p>
 */
package com.citycopilot.mcp;
