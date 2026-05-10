# Round Archive

本目录用于保存需要独立归档的开发轮次。

第一版不强制每个 commit 都创建归档目录。只有当一轮包含较多证据、方案对比、验收材料或回滚说明时，才需要建立独立归档。

## 命名规则

```text
YYYY-MM-DD-short-round-name/
```

示例：

```text
2026-05-01-docs-readme-tree/
2026-05-01-android-vm-performance-profile/
```

## 建议内容

```text
README.md
summary.md
evidence.md
```

- `README.md`：说明本轮问题、范围、结论和 commit。
- `summary.md`：说明支持了什么、否定了什么、下一轮必须继承什么。
- `evidence.md`：记录关键命令、验收摘要和证据路径。

大型产物、日志、截图原件、APK、VM 镜像和私钥仍然只能放在 `artifacts/`，不得放入归档目录提交。
