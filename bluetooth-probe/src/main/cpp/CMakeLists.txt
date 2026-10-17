cmake_minimum_required(VERSION 3.10)
project(usb_bt_hci C)
add_library(usb_bt_hci SHARED usb_bt_hci.c)
target_compile_options(usb_bt_hci PRIVATE -O2 -Wall)
