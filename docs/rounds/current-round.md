# Current Round

## Round Goal

建立按提交计算的开发轮次流程。

## Why Now

仓库已经完成 rootfs、QEMU、Android spike 等多条验证链路，后续继续开发前需要一个强约束流程，避免功能、文档、验收和架构边界混在一起。

## Scope

- 新增 `docs/rounds/` 下的轮次流程文档。
- 修改仓库处理流程，明确“一轮 = 一个非平凡 commit”。
- 在根 README 加入开发轮次入口。

## Non-Goals

- 不新增自动化脚本。
- 不修改 Android/QEMU/rootfs 实现。
- 不提交 artifacts、APK、二进制、日志或截图。
- 不改写 `docs/05-open-questions.md` 的内容。

## Acceptance Criteria

- [x] `docs/rounds/` 包含当前轮次、时间线、结论、backlog 和归档说明。
- [x] `docs/01-repository-workflow.md` 明确每个非平凡 commit 必须对应一轮。
- [x] `README.md` 能导航到开发轮次流程。
- [x] 本轮只修改文档，不改变 Android/QEMU 行为。
- [x] 不提交 artifacts 或已有无关改动。

## Planned Commit

`docs: add development round workflow`

## Notes

- `docs/05-open-questions.md` 当前已有用户认可的未提交改动，本轮不触碰。
- 第一版不做脚本自动化，避免过早固化流程实现。
