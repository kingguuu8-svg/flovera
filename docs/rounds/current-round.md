# Current Round

## Round Goal

规划沙箱设计目标，并列出可参考的成熟工程案例。

## Why Now

`docs/05-open-questions.md` 已明确“借鉴成熟实现而不是照抄”是当前开放问题。继续扩展实现前，需要先把沙箱目标、边界和可借鉴案例写清，减少自创设计空间。

## Scope

- 新增沙箱设计目标文档。
- 列出 Android AVF、Firecracker、gVisor、Flatpak、Crostini/Termina 等参考案例。
- 在根 README 加入沙箱设计目标入口。
- 更新本轮时间线和必要的开发结论。

## Non-Goals

- 不实现新的 sandbox 代码。
- 不修改 Android/QEMU/rootfs 行为。
- 不把任何参考案例照抄成当前方案。
- 不提交 artifacts、APK、二进制、日志或截图。
- 不改写 `docs/05-open-questions.md` 的内容。

## Acceptance Criteria

- [x] 文档明确第一阶段沙箱设计目标和非目标。
- [x] 文档列出可参考工程案例，并区分“借鉴什么”和“不照抄什么”。
- [x] 文档明确 SSH/JSch/dropbear 只是当前验收控制通道，不是长期协议。
- [x] README 能导航到沙箱设计目标文档。
- [x] 本轮只修改文档，不改变 Android/QEMU 行为。

## Planned Commit

`docs: define sandbox design targets`

## Notes

- `docs/05-open-questions.md` 当前已有用户认可的未提交改动，本轮不触碰。
- 参考案例只吸收抽象和边界，不复制平台实现。
