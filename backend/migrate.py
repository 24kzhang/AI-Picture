"""程序化执行数据库迁移。

绕过 alembic.ini（其含中文注释，中文 Windows 的 GBK 区域设置下
alembic 以 encoding="locale" 读取会解码失败；连接串本就由
migrations/env.py 从应用配置读取，ini 中只有 script_location 有用）。
"""

from pathlib import Path

from alembic import command
from alembic.config import Config


def main() -> None:
    cfg = Config()
    cfg.set_main_option("script_location", str(Path(__file__).parent / "migrations"))
    command.upgrade(cfg, "head")


if __name__ == "__main__":
    main()
