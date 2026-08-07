# Otix

Proyecto Android para controlar un robot con ESP32-C3 Super Mini por medio de Bluetooth.

## Aplicación Android

Funciones incluidas:

- Conexión y desconexión BLE
- Ocho direcciones y parada
- Velocidades lenta, media y rápida
- Comandos de voz en español
- Control por inclinación del teléfono
- Demostración automática
- Temporizador de parada
- Parada automática al salir de la aplicación
- Latido periódico para el sistema de seguridad del firmware

## Firmware

Configuración recomendada en Arduino IDE:

- Placa: ESP32C3 Dev Module
- USB CDC On Boot: Enabled
- Core ESP32 para Arduino: 3.x

## Conexiones L293D

| ESP32-C3 | L293D | Función |
|---|---|---|
| GPIO4 | EN1,2 | Velocidad motor izquierdo |
| GPIO2 | 1A | Dirección izquierda 1 |
| GPIO3 | 2A | Dirección izquierda 2 |
| GPIO7 | EN3,4 | Velocidad motor derecho |
| GPIO5 | 3A | Dirección derecha 1 |
| GPIO6 | 4A | Dirección derecha 2 |

El ESP32-C3 y el L293D deben compartir GND. La alimentación de los motores debe ser independiente de la salida de 3,3 V del ESP32-C3.

## Protocolo

| Comando | Acción |
|---|---|
| F | Adelante |
| B | Atrás |
| L | Izquierda |
| R | Derecha |
| J | Adelante izquierda |
| Q | Adelante derecha |
| M | Atrás izquierda |
| H | Atrás derecha |
| S | Detener |
| V | Lento |
| W | Medio |
| X | Rápido |
| P | Latido de seguridad |

El firmware detiene los motores si deja de recibir comunicación durante 1,2 segundos.
