# Monica Android 1.0.330 · 千石抚子（SengokuNadeko）

紧急补丁 / Emergency hotfix

## 中文

对不起，这次是我的疏漏。327–329 版本中，部分用户把「自动锁定」设为「立即」后，即使输入正确密码也无法进入软件。密码管理器在需要使用时却打不开，给大家带来了不便，也让大家担心自己的数据。没能在发布前发现这个问题，是我的责任，向受影响的用户认真道歉，也感谢大家提供录屏和日志帮助定位。

1.0.330 是针对这个问题发布的紧急补丁，**「立即锁定」导致无法进入软件的问题已经修复**。

回溯时怀疑，这个回归可能与当时升级依赖有关，但具体引入点尚未完全确认。已经确认的直接原因是：同步和界面刷新中的会话检查，会把刚刚完成的认证再次撤销。这次已修复这两处误锁定，并保留重新进入应用、主动锁定和错误密码验证的保护。

已经遇到此问题的用户，请使用同一渠道、同一签名的 1.0.330 直接覆盖升级；不需要先回退到 326，也不要卸载或清除应用数据。本次补丁不修改密码算法、加密格式或数据库结构。已经验证旧版数据上的覆盖升级、原密码解锁、旧记录读取、新记录保存和加密备份恢复；用户的 HyperOS 真机仍需进一步反馈确认。

最后保留一下我的调侃：**是因为我把代号写成千石抚子的原因吗？？？逆抚子来的。**

## English

I am sorry. This was my oversight. In versions 327–329, some users who set Auto-lock to Immediately could no longer enter the app even with the correct password. A password manager being unavailable when you need it causes real inconvenience and worry about your data. I take responsibility for missing this before release, and I sincerely apologize to everyone affected. Thank you for the recordings and logs that helped identify it.

1.0.330 is an emergency hotfix. **The login failure caused by Immediately auto-lock has been fixed.**

Looking back, this regression may be related to the dependency upgrades at the time, but the exact change that introduced it has not yet been confirmed. The direct cause is confirmed: session checks during sync and UI updates could revoke authentication immediately after it succeeded. Both paths are fixed, while authentication on app re-entry, explicit locking and wrong-password rejection remain in place.

If you are affected, install 1.0.330 over your existing app using the same distribution channel and signing identity. You no longer need to roll back to 326 first. Please do not uninstall the app or clear its data. This patch does not change password algorithms, encryption formats or the database schema. Upgrade tests with existing data, original-password unlock, old-record reads, new writes and encrypted-backup restoration have passed; confirmation on users' physical HyperOS devices is still pending.

And, keeping my joke: **Was this because I named the codename Sengoku Nadeko??? Must be Nadeko in her rebellious mood (逆抚子).**
