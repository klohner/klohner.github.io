import time
import board
import digitalio

import usb_hid
from adafruit_hid.keyboard import Keyboard,Keycode
 
led = digitalio.DigitalInOut(board.LED)
led.direction = digitalio.Direction.OUTPUT

keyboard = Keyboard(usb_hid.devices)
 
btnA = digitalio.DigitalInOut(board.GP11)
btnA.direction = digitalio.Direction.INPUT
btnA.pull = digitalio.Pull.UP
btnB = digitalio.DigitalInOut(board.GP10)
btnB.direction = digitalio.Direction.INPUT
btnB.pull = digitalio.Pull.UP
btnC = digitalio.DigitalInOut(board.GP12)
btnC.direction = digitalio.Direction.INPUT
btnC.pull = digitalio.Pull.UP
btnD = digitalio.DigitalInOut(board.GP13)
btnD.direction = digitalio.Direction.INPUT
btnD.pull = digitalio.Pull.UP

already_pressing_BTN = False

while True:
    if not(btnA.value and btnB.value and btnC.value and btnD.value):
        if not(already_pressing_BTN):
            already_pressing_BTN = True
            if not(btnA.value):
                keyboard.send(Keycode.P)
            elif not(btnB.value):
                keyboard.send(Keycode.R)
            elif not(btnC.value):
                keyboard.send(Keycode.KEYPAD_PLUS)
            elif not(btnD.value):
                keyboard.send(Keycode.KEYPAD_MINUS)
            led.value = True  # Provide visual feedback
    else:
        already_pressing_BTN = False
        led.value = False # Turn off LED on release
        time.sleep(0.1)
    time.sleep(0.01)
