CPU使用说明
可以在主目录执行以下三条make
```
1：make irom
    执行需要rv32工具链，可以把C语言程序编译成基础的RV32I指令的16进制格式，让chisel读取
    执行前把你的C语言文件放在./src/test/main.c中，然后执行这个命令
    之后执行构建verilog代码的命令时，指令寄存器会自动初始化为你的C语言文件编译结果

2：make fst
    需要verilator 5.008版本，gtkwave 3.3.104版本（其余版本verilator可能出现问题）
    构建verilog并执行仿真，之后利用gtkwave打开当前仿真波形图

    现在已经调整成使用surfer打开，观察波形图请下载surfer(或者把Makefile里面改成gtkwave)

3：make
    需要verilator 5.008版本
    构建verilog并执行仿真，输出程序return值(a0寄存器返回值)，
    分支预测准确度,执行程序的周期,和CPI

如果你没有rv32工具链，请直接把你的测试用16进制文件放在./src/test/build/inst_be.hex里面
整个文件只能有每行8位的十六进制代码，不能有任何其他内容

config里面有三个内存路径，分别是dhrystone，coremark和自定义测试，根据需要选择不同测试，
同时修改csrc/sim_main.cpp 里面的代码开始和结束地址
 
config里面的发射宽度选项可以调整单发射，双发射和四发射，调整完之后需要修改csrc/sim_main.cpp 
里面的发射宽度支持

示例
fc010113
02112e23
02812c23
04010413
fe042623
00200793
fef42423
00300793
fef42223
00100793
```
项目文件src目录 
```
src/
├── main
│   └── scala
│       ├── config(配置文件)
│       │   └── Configs.scala(存放数据和指令宽度之类定义的文件)
│       ├── rv32isc(存放项目代码的文件,后续的模块代码写在这里)
│       │   ├── chisel.scala(主文件，用于连接代码和产生verilog文件)
│       │   └── ...
│       └── (存放端口文件和ALU命令文件)
│           ├── Bundles.scala(打包的端口文件,把自己模块的端口Bundles放入这里)
│           └── ...
└──test(测试用指令寄存器初始化内容)
    ├── build(构建的16进制文件目录)
    ├── c_to_hex.sh(生成16进制文件的脚本)
    ├── linker.ld
    ├── main
    ├── main.c
    └── Makefile

```
其余重要目录    
```
.
├── build.sbt(sbt 构建文件)
├── build.sc
├── csrc(仿真测试程序)
│   ├── file_read.h(读取文件的函数，当没有内置irom时使用)
│   ├── sim_fst_main.cpp(fst波形文件仿真函数)
│   └── sim_main.cpp(主测试函数，输出执行数据)
├── Makefile
├── README.md
├── SIM_CPU.v(产生的CPU文件)
├── TEST.gtkw(gtkwave波形展开保存文件，避免每次都要手动打开)
└── vsrc
    └── top_module.v(verilator构建用verilog代码)
```
```
提示:
    1：需要重复使用的常量请写成宏定义到Configs文件里面

    2：每个人的模块输出端口麻烦封装成bundle到Bundles文件夹里

    3：请根据输出端的内容写入的模块分类编写bundle，不要把所有的端口写成一个bundle(也就是说你的输
    出端口应该是一堆分类好的bundle和一些独立端口)

    4：每个模块的输入端请用flipped别人上一级的输出模块，不要自己命名

    5：每次提交分支前请先git pull，然后merge最新的分支，绝对！绝对！绝对！！不要git push -f

    6：每个人写的模快设置成独立文件，共同编写的代码文件只有Configs和Bundles（这俩也是各写各的）

    7：写Bundles和Configs文件打上注释，每个人写的文件开头写上自己的名字

    8：每次提交请简要说明完成的内容

    9：发现其他人的代码问题先在群里汇报，不要自己改然后私自提交

    10: 不要随意git add除了代码文件以外的其他文件

```
